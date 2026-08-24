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

  public InventoryView inventory(UUID playerId) {
    try {
      return new InventoryView(snapshot(playerId, false), true);
    } catch (ServiceException failure) {
      if (!"inventory_unavailable".equals(failure.code())) throw failure;
      return new InventoryView(snapshot(playerId, true), false);
    }
  }

  public DiscardResult discard(DiscardRequest request) {
    validateKeyAndQuantity(request.idempotencyKey(), request.quantity());
    Existing existing = find(request.userId(), request.idempotencyKey());
    if (existing != null) return replayDiscard(existing, request);
    InventorySnapshot snapshot = snapshot(request.playerId(), request.allowOffline());
    ItemEnvelope selected = select(snapshot, request.expectedPayloadHash(), request.quantity());
    DiscardResult concurrent = beginDiscard(request, selected);
    if (concurrent != null) return concurrent;
    String operationId = "inventory-discard:" + request.userId() + ":" + request.idempotencyKey();
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
      handleApplyFailure(request.userId(), request.idempotencyKey(), result);
      throw new ServiceException("inventory_rejected", "Inventory operation was rejected");
    }
    if (success.value().removed().size() != 1
        || success.value().removed().get(0).count() != request.quantity()) {
      throw new ServiceException(
          "inventory_outcome_unknown", "Inventory operation requires reconciliation");
    }
    DiscardResult discarded = new DiscardResult("SUCCESS", request.quantity(), "refresh-required");
    try {
      database.inTransaction(
          connection -> {
            completeDiscard(connection, request, gson.toJson(discarded));
            return null;
          });
      return discarded;
    } catch (RuntimeException failure) {
      compensate(
          request.userId(),
          request.playerId(),
          request.idempotencyKey(),
          success.value().removed().get(0));
      throw failure;
    }
  }

  public List<SharedCommerceService.MarketMatch> matches(MatchRequest request) {
    if (request.quantity() < 1 || request.quantity() > 64) {
      throw new ServiceException("invalid_quantity", "Quantity must be between 1 and 64");
    }
    InventorySnapshot snapshot = snapshot(request.playerId(), request.allowOffline());
    ItemEnvelope selected = select(snapshot, request.expectedPayloadHash(), request.quantity());
    return commerce.matchingBuyListings(request.userId(), selected, request.quantity());
  }

  public SharedCommerceService.MarketTrade fulfill(FulfillRequest request) {
    validateKeyAndQuantity(request.idempotencyKey(), request.quantity());
    Existing existing = find(request.userId(), request.idempotencyKey());
    if (existing != null) return replayFulfill(existing, request);
    InventorySnapshot snapshot = snapshot(request.playerId(), request.allowOffline());
    ItemEnvelope selected = select(snapshot, request.expectedPayloadHash(), request.quantity());
    SharedCommerceService.MarketTrade concurrent = beginFulfill(request, selected);
    if (concurrent != null) return concurrent;
    String operationId = "market-fulfill:" + request.userId() + ":" + request.idempotencyKey();
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
      handleApplyFailure(request.userId(), request.idempotencyKey(), result);
      throw new ServiceException("inventory_rejected", "Inventory operation was rejected");
    }
    if (success.value().removed().size() != 1
        || success.value().removed().get(0).count() != request.quantity()) {
      throw new ServiceException(
          "inventory_outcome_unknown", "Inventory operation requires reconciliation");
    }
    ItemEnvelope removed = success.value().removed().get(0);
    try {
      return database.inTransaction(
          connection -> {
            SharedCommerceService.MarketTrade trade =
                commerce.fulfillBuyListing(
                    connection,
                    new SharedCommerceService.MarketFulfillRequest(
                        request.userId(),
                        request.playerId(),
                        request.listingId(),
                        request.quantity(),
                        request.idempotencyKey(),
                        request.expectedUnitPrice(),
                        request.expectedBuyerTotal()),
                    removed);
            completeFulfill(connection, request, trade.id(), gson.toJson(trade));
            return trade;
          });
    } catch (RuntimeException failure) {
      compensate(request.userId(), request.playerId(), request.idempotencyKey(), removed);
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

  private DiscardResult beginDiscard(DiscardRequest request, ItemEnvelope selected) {
    try {
      database.inTransaction(
          connection -> {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "INSERT INTO inventory_operations (user_id,idempotency_key,action,state,"
                        + "slot_index,container_slot,item_fingerprint,quantity,result_json)"
                        + " VALUES (?,?,'INVENTORY_DISCARD','PENDING',-1,NULL,?,?,?)")) {
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
      if (existing != null) return replayDiscard(existing, request);
      throw duplicate;
    }
  }

  private SharedCommerceService.MarketTrade beginFulfill(
      FulfillRequest request, ItemEnvelope selected) {
    try {
      database.inTransaction(
          connection -> {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "INSERT INTO inventory_operations (user_id,idempotency_key,action,state,"
                        + "slot_index,container_slot,item_fingerprint,quantity,result_json)"
                        + " VALUES (?,?,'MARKET_FULFILL','PENDING',-1,NULL,?,?,?)")) {
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
      if (existing != null) return replayFulfill(existing, request);
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

  private void completeDiscard(Connection connection, DiscardRequest request, String resultJson)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE inventory_operations SET state='SUCCESS',reference_id=0,result_json=?,"
                + "updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND idempotency_key=?"
                + " AND action='INVENTORY_DISCARD' AND state='PENDING'")) {
      statement.setString(1, resultJson);
      statement.setLong(2, request.userId());
      statement.setString(3, request.idempotencyKey());
      if (statement.executeUpdate() != 1) {
        throw new ServiceException("idempotency_conflict", "Discard operation state changed");
      }
    }
  }

  private void completeFulfill(
      Connection connection, FulfillRequest request, long tradeId, String resultJson)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE inventory_operations SET state='SUCCESS',reference_id=?,result_json=?,"
                + "updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND idempotency_key=?"
                + " AND action='MARKET_FULFILL' AND state='PENDING'")) {
      statement.setLong(1, tradeId);
      statement.setString(2, resultJson);
      statement.setLong(3, request.userId());
      statement.setString(4, request.idempotencyKey());
      if (statement.executeUpdate() != 1) {
        throw new ServiceException("idempotency_conflict", "Fulfill operation state changed");
      }
    }
  }

  private void compensate(Request request, ItemEnvelope item) {
    compensate(request.userId(), request.playerId(), request.idempotencyKey(), item);
  }

  private void compensate(long userId, UUID playerId, String idempotencyKey, ItemEnvelope item) {
    try {
      InventorySnapshot current = snapshot(playerId, true);
      PlatformResult<InventoryMutationResult> result =
          inventories
              .compareAndApply(
                  new InventoryMutation(
                      "inventory-compensate:" + userId + ":" + idempotencyKey,
                      playerId,
                      current.version(),
                      List.of(item),
                      List.of()))
              .toCompletableFuture()
              .get(INVENTORY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (result instanceof PlatformResult.Success<InventoryMutationResult> success
          && success.value().remainder().isEmpty()) {
        reject(userId, idempotencyKey, "persist_failed_compensated");
        return;
      }
    } catch (Exception ignored) {
      // PENDING is deliberately retained for operator reconciliation.
    }
    throw new ServiceException(
        "inventory_outcome_unknown", "Escrow compensation requires reconciliation");
  }

  private void handleApplyFailure(Request request, PlatformResult<?> result) {
    handleApplyFailure(request.userId(), request.idempotencyKey(), result);
  }

  private void handleApplyFailure(long userId, String idempotencyKey, PlatformResult<?> result) {
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
    reject(userId, idempotencyKey, code);
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
    reject(request.userId(), request.idempotencyKey(), code);
  }

  private void reject(long userId, String idempotencyKey, String code) {
    database.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE inventory_operations SET state='REJECTED',error_code=?,"
                      + "updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND idempotency_key=?"
                      + " AND state='PENDING'")) {
            statement.setString(1, code);
            statement.setLong(2, userId);
            statement.setString(3, idempotencyKey);
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
                  "SELECT"
                      + " action,state,reference_id,error_code,item_fingerprint,quantity,result_json"
                      + " FROM inventory_operations WHERE user_id=? AND idempotency_key=?")) {
            statement.setLong(1, userId);
            statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) {
              if (!rows.next()) return null;
              Object reference = rows.getObject(3);
              return new Existing(
                  rows.getString(1),
                  rows.getString(2),
                  reference instanceof Number number ? number.longValue() : null,
                  rows.getString(4),
                  rows.getString(5),
                  rows.getInt(6),
                  rows.getString(7));
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

  private DiscardResult replayDiscard(Existing existing, DiscardRequest request) {
    if (!"INVENTORY_DISCARD".equals(existing.action())
        || existing.quantity() != request.quantity()
        || (request.expectedPayloadHash() != null
            && !request.expectedPayloadHash().equals(existing.itemFingerprint()))) {
      throw new ServiceException("idempotency_conflict", "Idempotency request does not match");
    }
    if ("SUCCESS".equals(existing.state())) {
      return new DiscardResult("SUCCESS", existing.quantity(), "refresh-required");
    }
    if ("REJECTED".equals(existing.state())) {
      throw new ServiceException(
          existing.errorCode() == null ? "inventory_rejected" : existing.errorCode(),
          "Discard operation was rejected");
    }
    throw new ServiceException(
        "inventory_outcome_unknown", "Discard operation requires reconciliation");
  }

  private SharedCommerceService.MarketTrade replayFulfill(
      Existing existing, FulfillRequest request) {
    if (!"MARKET_FULFILL".equals(existing.action())
        || existing.quantity() != request.quantity()
        || (request.expectedPayloadHash() != null
            && !request.expectedPayloadHash().equals(existing.itemFingerprint()))) {
      throw new ServiceException("idempotency_conflict", "Idempotency request does not match");
    }
    if ("SUCCESS".equals(existing.state()) && existing.referenceId() != null) {
      return commerce.marketTrade(existing.referenceId());
    }
    if ("REJECTED".equals(existing.state())) {
      throw new ServiceException(
          existing.errorCode() == null ? "inventory_rejected" : existing.errorCode(),
          "Fulfill operation was rejected");
    }
    throw new ServiceException(
        "inventory_outcome_unknown", "Fulfill operation requires reconciliation");
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

  private static void validateKeyAndQuantity(String key, int quantity) {
    if (key == null || key.isBlank() || key.trim().length() > 96) {
      throw new ServiceException("invalid_idempotency", "A valid idempotency key is required");
    }
    if (quantity < 1 || quantity > 64) {
      throw new ServiceException("invalid_quantity", "Quantity must be between 1 and 64");
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

  public record InventoryView(InventorySnapshot snapshot, boolean online) {}

  public record DiscardRequest(
      long userId,
      UUID playerId,
      int quantity,
      String idempotencyKey,
      String expectedPayloadHash,
      boolean allowOffline) {}

  public record DiscardResult(String state, int discardedQuantity, String revision) {}

  public record MatchRequest(
      long userId,
      UUID playerId,
      int quantity,
      String expectedPayloadHash,
      boolean allowOffline) {}

  public record FulfillRequest(
      long userId,
      UUID playerId,
      long listingId,
      int quantity,
      String idempotencyKey,
      String expectedPayloadHash,
      boolean allowOffline,
      Long expectedUnitPrice,
      Long expectedBuyerTotal) {}

  private record Existing(
      String action,
      String state,
      Long referenceId,
      String errorCode,
      String itemFingerprint,
      int quantity,
      String resultJson) {}
}
