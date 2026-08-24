package com.webshopx;

import com.google.gson.Gson;
import com.webshopx.platform.InventoryTypes.InventoryMutation;
import com.webshopx.platform.InventoryTypes.InventoryMutationResult;
import com.webshopx.platform.InventoryTypes.InventoryRemoval;
import com.webshopx.platform.InventoryTypes.InventorySnapshot;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformPorts.InventoryGateway;
import com.webshopx.platform.PlatformResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Durable, fail-closed bridge between native inventory mutation and market escrow. */
public final class SharedMarketEscrowService {
  private static final long INVENTORY_TIMEOUT_SECONDS = 15;
  private final DatabaseManager database;
  private final SharedCommerceService commerce;
  private final InventoryGateway inventories;
  private final Gson gson = CommerceJson.create();

  public SharedMarketEscrowService(
      DatabaseManager database, SharedCommerceService commerce, InventoryGateway inventories) {
    this.database = Objects.requireNonNull(database, "database");
    this.commerce = Objects.requireNonNull(commerce, "commerce");
    this.inventories = Objects.requireNonNull(inventories, "inventories");
  }

  public SharedCommerceService.Listing createSellListing(Request request) {
    validate(request);
    Existing existing = find(request.userId(), request.idempotencyKey());
    if (existing != null) return replay(existing);

    InventorySnapshot snapshot = snapshot(request.playerId(), request.allowOffline());
    ItemEnvelope selected = select(snapshot, request.expectedPayloadHash(), request.quantity());
    SharedCommerceService.Listing concurrent = begin(request, selected);
    if (concurrent != null) return concurrent;

    String operationId = "market-listing:" + request.userId() + ":" + request.idempotencyKey();
    PlatformResult<InventoryMutationResult> result;
    try {
      result =
          inventories
              .compareAndApply(
                  new InventoryMutation(
                      operationId,
                      request.playerId(),
                      snapshot.version(),
                      List.of(),
                      List.of(new InventoryRemoval(selected, request.quantity()))))
              .toCompletableFuture()
              .get(INVENTORY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new ServiceException(
          "inventory_outcome_unknown", "Inventory operation requires reconciliation");
    }
    if (!(result instanceof PlatformResult.Success<InventoryMutationResult> success)) {
      handleApplyFailure(request, result);
      throw new ServiceException("inventory_rejected", "Inventory operation was rejected");
    }
    InventoryMutationResult applied = success.value();
    if (applied.removed().size() != 1 || applied.removed().get(0).count() != request.quantity()) {
      throw new ServiceException(
          "inventory_outcome_unknown", "Inventory operation requires reconciliation");
    }
    ItemEnvelope escrowItem = applied.removed().get(0);
    try {
      return database.inTransaction(
          connection -> {
            SharedCommerceService.Listing listing =
                commerce.createListing(
                    connection,
                    new SharedCommerceService.ListingRequest(
                        request.userId(),
                        request.playerId(),
                        request.currency(),
                        request.price(),
                        request.quantity(),
                        escrowItem,
                        request.remark()));
            complete(connection, request, listing.id(), gson.toJson(listing));
            return listing;
          });
    } catch (RuntimeException failure) {
      compensate(request, escrowItem);
      throw failure;
    }
  }

  private InventorySnapshot snapshot(UUID playerId, boolean allowOffline) {
    try {
      PlatformResult<InventorySnapshot> result =
          inventories
              .snapshot(playerId, allowOffline)
              .toCompletableFuture()
              .get(INVENTORY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (result instanceof PlatformResult.Success<InventorySnapshot> success) {
        return success.value();
      }
      throw platformFailure(result);
    } catch (ServiceException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new ServiceException("inventory_unavailable", "Inventory snapshot is unavailable");
    }
  }

  private static ItemEnvelope select(
      InventorySnapshot snapshot, String expectedPayloadHash, int quantity) {
    return snapshot.items().stream()
        .filter(
            item -> expectedPayloadHash == null || item.payloadHash().equals(expectedPayloadHash))
        .filter(item -> item.count() >= quantity)
        .findFirst()
        .orElseThrow(
            () -> new ServiceException("insufficient_item", "No matching inventory stack exists"));
  }

  private SharedCommerceService.Listing begin(Request request, ItemEnvelope selected) {
    try {
      database.inTransaction(
          connection -> {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "INSERT INTO inventory_operations (user_id,idempotency_key,action,state,"
                        + "slot_index,container_slot,item_fingerprint,quantity,result_json)"
                        + " VALUES (?,?,'MARKET_LIST','PENDING',-1,NULL,?,?,?)")) {
              statement.setLong(1, request.userId());
              statement.setString(2, request.idempotencyKey());
              statement.setString(3, selected.payloadHash());
              statement.setInt(4, request.quantity());
              statement.setString(5, gson.toJson(request));
              statement.executeUpdate();
            }
            return null;
          });
      return null;
    } catch (RuntimeException duplicate) {
      Existing existing = find(request.userId(), request.idempotencyKey());
      if (existing != null) return replay(existing);
      throw duplicate;
    }
  }

  private void complete(Connection connection, Request request, long listingId, String resultJson)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE inventory_operations SET state='SUCCESS',reference_id=?,result_json=?,"
                + "updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND idempotency_key=?"
                + " AND action='MARKET_LIST' AND state='PENDING'")) {
      statement.setLong(1, listingId);
      statement.setString(2, resultJson);
      statement.setLong(3, request.userId());
      statement.setString(4, request.idempotencyKey());
      if (statement.executeUpdate() != 1) {
        throw new ServiceException("idempotency_conflict", "Listing operation state changed");
      }
    }
  }

  private void compensate(Request request, ItemEnvelope item) {
    try {
      InventorySnapshot current = snapshot(request.playerId(), true);
      PlatformResult<InventoryMutationResult> result =
          inventories
              .compareAndApply(
                  new InventoryMutation(
                      "market-listing-compensate:"
                          + request.userId()
                          + ":"
                          + request.idempotencyKey(),
                      request.playerId(),
                      current.version(),
                      List.of(item),
                      List.of()))
              .toCompletableFuture()
              .get(INVENTORY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (result instanceof PlatformResult.Success<InventoryMutationResult> success
          && success.value().remainder().isEmpty()) {
        reject(request, "market_persist_failed_compensated");
        return;
      }
    } catch (Exception ignored) {
      // PENDING is deliberately retained for operator reconciliation.
    }
    throw new ServiceException(
        "inventory_outcome_unknown", "Escrow compensation requires reconciliation");
  }

  private void handleApplyFailure(Request request, PlatformResult<?> result) {
    if (result instanceof PlatformResult.UnknownOutcome<?>) {
      throw new ServiceException(
          "inventory_outcome_unknown", "Inventory operation requires reconciliation");
    }
    String code =
        result instanceof PlatformResult.Rejected<?> rejected
            ? rejected.errorCode()
            : result instanceof PlatformResult.Conflict<?>
                ? "inventory_conflict"
                : "inventory_unavailable";
    reject(request, code);
    throw platformFailure(result);
  }

  private static ServiceException platformFailure(PlatformResult<?> result) {
    if (result instanceof PlatformResult.Rejected<?> rejected) {
      return new ServiceException(rejected.errorCode().toLowerCase(), rejected.messageKey());
    }
    if (result instanceof PlatformResult.Conflict<?>) {
      return new ServiceException("inventory_conflict", "Inventory changed; refresh and retry");
    }
    if (result instanceof PlatformResult.UnknownOutcome<?>) {
      return new ServiceException(
          "inventory_outcome_unknown", "Inventory operation requires reconciliation");
    }
    return new ServiceException("inventory_unavailable", "Inventory capability is unavailable");
  }

  private void reject(Request request, String code) {
    database.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE inventory_operations SET state='REJECTED',error_code=?,"
                      + "updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND idempotency_key=?"
                      + " AND state='PENDING'")) {
            statement.setString(1, code);
            statement.setLong(2, request.userId());
            statement.setString(3, request.idempotencyKey());
            statement.executeUpdate();
          }
          return null;
        });
  }

  private Existing find(long userId, String key) {
    return database.withConnection(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT action,state,reference_id,error_code FROM inventory_operations"
                      + " WHERE user_id=? AND idempotency_key=?")) {
            statement.setLong(1, userId);
            statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) {
              if (!rows.next()) return null;
              Object reference = rows.getObject(3);
              return new Existing(
                  rows.getString(1),
                  rows.getString(2),
                  reference instanceof Number number ? number.longValue() : null,
                  rows.getString(4));
            }
          }
        });
  }

  private SharedCommerceService.Listing replay(Existing existing) {
    if (!"MARKET_LIST".equals(existing.action())) {
      throw new ServiceException(
          "idempotency_conflict", "Idempotency key belongs to another action");
    }
    if ("SUCCESS".equals(existing.state()) && existing.referenceId() != null) {
      return commerce.listing(existing.referenceId());
    }
    if ("REJECTED".equals(existing.state())) {
      throw new ServiceException(
          existing.errorCode() == null ? "inventory_rejected" : existing.errorCode(),
          "Listing operation was rejected");
    }
    throw new ServiceException(
        "inventory_outcome_unknown", "Listing operation requires reconciliation");
  }

  private static void validate(Request request) {
    Objects.requireNonNull(request, "request");
    if (request.idempotencyKey() == null
        || request.idempotencyKey().isBlank()
        || request.idempotencyKey().trim().length() > 96) {
      throw new ServiceException("invalid_idempotency", "A valid idempotency key is required");
    }
    if (request.quantity() < 1 || request.quantity() > 64 || request.price() < 1) {
      throw new ServiceException("invalid_listing", "Listing price or quantity is invalid");
    }
  }

  public record Request(
      long userId,
      UUID playerId,
      CurrencyType currency,
      long price,
      int quantity,
      String idempotencyKey,
      String expectedPayloadHash,
      boolean allowOffline,
      String remark) {}

  private record Existing(String action, String state, Long referenceId, String errorCode) {}
}
