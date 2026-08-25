package com.webshopx;

import com.google.gson.Gson;
import com.webshopx.core.ItemEnvelopeBinaryCodec;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformResult;
import com.webshopx.platform.SupplyInventoryGateway;
import com.webshopx.platform.SupplyInventoryGateway.SupplyLocation;
import com.webshopx.platform.SupplyInventoryGateway.SupplySnapshot;
import com.webshopx.platform.SupplyInventoryGateway.SupplyWithdrawal;
import com.webshopx.platform.SupplyInventoryGateway.SupplyWithdrawalRequest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Cluster-serialized native supply-container replenishment. */
public final class SharedSupplyService {
  private static final long TIMEOUT_SECONDS = 15;
  private static final Duration LEASE_DURATION = Duration.ofSeconds(45);
  private final DatabaseManager database;
  private final SharedCommerceService commerce;
  private final SupplyInventoryGateway gateway;
  private final String serverId;
  private final Gson gson = CommerceJson.create();
  private final ItemEnvelopeBinaryCodec envelopes = new ItemEnvelopeBinaryCodec();

  public SharedSupplyService(
      DatabaseManager database,
      SharedCommerceService commerce,
      SupplyInventoryGateway gateway,
      String serverId) {
    this.database = Objects.requireNonNull(database, "database");
    this.commerce = Objects.requireNonNull(commerce, "commerce");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.serverId = Objects.requireNonNull(serverId, "serverId");
    ensureSchema();
  }

  public SupplyInspection inspect(UUID playerId, SupplyLocation location) {
    PlatformResult<SupplySnapshot> result;
    try {
      result = gateway.inspect(playerId, location).toCompletableFuture()
          .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new ServiceException("supply_unavailable", "Supply container is unavailable");
    }
    if (result instanceof PlatformResult.Success<SupplySnapshot> success) {
      return new SupplyInspection(success.value().version(), success.value().items());
    }
    if (result instanceof PlatformResult.Rejected<SupplySnapshot>) {
      throw new ServiceException(
          "supply_inspection_denied", "Stand near a supply container you can access");
    }
    throw new ServiceException("supply_unavailable", "Supply container is unavailable");
  }

  public SupplyCreateResult create(SupplyCreateRequest request) {
    SharedCommerceService.Listing replay = commerce.replaySupplyListing(
        new SharedCommerceService.SupplyListingReplayRequest(
            request.ownerUserId(), request.currency(), request.price(),
            request.expectedPayloadHash(), request.location().world(), request.location().x(),
            request.location().y(), request.location().z(), request.batchSize(), request.maxStock(),
            request.accessProtected(), request.idempotencyKey(), request.remark()));
    if (replay != null) {
      SupplyRefreshResult refreshed = refresh(
          replay.id(), request.ownerUserId(), "supply-create:" + replay.id());
      return new SupplyCreateResult(commerce.listing(replay.id()), refreshed);
    }
    SupplyInspection inspection = inspect(request.ownerId(), request.location());
    ItemEnvelope template = inspection.items().stream()
        .filter(item -> item.payloadHash().equals(request.expectedPayloadHash()))
        .findFirst()
        .orElseThrow(() -> new ServiceException(
            "supply_item_changed", "Supply item changed; inspect the container again"));
    SharedCommerceService.Listing listing = commerce.createSupplyListing(
        new SharedCommerceService.SupplyListingRequest(
            request.ownerUserId(), request.ownerId(), request.currency(), request.price(),
            template, request.location().world(), request.location().x(), request.location().y(),
            request.location().z(), request.batchSize(), request.maxStock(),
            request.accessProtected(), request.idempotencyKey(), request.remark()));
    SupplyRefreshResult refreshed = refresh(
        listing.id(), request.ownerUserId(), "supply-create:" + listing.id());
    return new SupplyCreateResult(commerce.listing(listing.id()), refreshed);
  }

  public SupplyRefreshResult refresh(long listingId, long requestedBy, String requestedOperationId) {
    recoverStaleOperations();
    SupplyListing listing = readListing(listingId);
    if (listing.sellerUserId() != requestedBy) {
      throw new ServiceException("forbidden", "Only the supply listing owner can refresh it");
    }
    SupplySnapshot snapshot = snapshot(listing.location());
    String operationId = requestedOperationId == null || requestedOperationId.isBlank()
        ? defaultOperationId(listing, snapshot.version()) : normalizeOperationId(requestedOperationId);
    SupplyRefreshResult replay = replay(operationId, listing.id(), requestedBy);
    if (replay != null) return replay;
    int capacity = Math.max(0, listing.maximumStock() - listing.currentStock());
    int requested = Math.min(listing.batchSize(), capacity);
    if (requested == 0) {
      return new SupplyRefreshResult(
          listing.id(), 0, listing.currentStock(), listing.maximumStock(),
          listing.loadedTotal(), listing.soldTotal(), listing.status(), operationId);
    }
    SupplyRefreshResult raced = acquire(
        listing, requestedBy, operationId, snapshot, requested);
    if (raced != null) return raced;
    PlatformResult<SupplyWithdrawal> outcome;
    try {
      outcome = gateway.compareAndWithdraw(new SupplyWithdrawalRequest(
              operationId, listing.location(), snapshot.version(),
              listing.item(), requested))
          .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (Exception failure) {
      markUnknown(operationId, "native supply outcome was not observed");
      throw new ServiceException(
          "supply_outcome_unknown", "Supply withdrawal requires reconciliation");
    }
    if (outcome instanceof PlatformResult.Success<SupplyWithdrawal> success) {
      try {
        return complete(listing, operationId, success.value());
      } catch (ServiceException failure) {
        if ("supply_outcome_unknown".equals(failure.code())) {
          markUnknown(operationId, failure.getMessage());
        }
        throw failure;
      }
    }
    if (outcome instanceof PlatformResult.UnknownOutcome<SupplyWithdrawal>) {
      markUnknown(operationId, "native supply gateway returned unknown outcome");
      throw new ServiceException(
          "supply_outcome_unknown", "Supply withdrawal requires reconciliation");
    }
    releaseFailed(operationId, platformMessage(outcome));
    if (outcome instanceof PlatformResult.Conflict<SupplyWithdrawal>) {
      throw new ServiceException("supply_conflict", "Supply container changed concurrently");
    }
    if (outcome instanceof PlatformResult.Unavailable<SupplyWithdrawal>) {
      throw new ServiceException("supply_unavailable", "Supply container is unavailable");
    }
    throw new ServiceException("supply_rejected", "Supply withdrawal was rejected");
  }

  /** Replays a platform-side durable result without issuing another withdrawal. */
  public SupplyRefreshResult reconcileUnknown(String operationId, long requestedBy) {
    recoverStaleOperations();
    SupplyOperation operation = readOperation(normalizeOperationId(operationId));
    if (operation.requestedBy() != requestedBy) {
      throw new ServiceException("forbidden", "Only the supply listing owner can reconcile it");
    }
    if ("SUCCESS".equals(operation.state())) return operation.result();
    if (!"UNKNOWN".equals(operation.state())) {
      throw new ServiceException("supply_not_unknown", "Supply operation is not unknown");
    }
    SupplyListing listing = readListing(operation.listingId());
    PlatformResult<SupplyWithdrawal> outcome;
    try {
      outcome = gateway.reconcile(new SupplyWithdrawalRequest(
              operation.operationId(), listing.location(), operation.expectedVersion(),
              listing.item(), operation.requestedQuantity()))
          .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new ServiceException(
          "supply_outcome_unknown", "Supply withdrawal still requires reconciliation");
    }
    if (outcome instanceof PlatformResult.Success<SupplyWithdrawal> success) {
      return complete(listing, operation.operationId(), success.value(), "UNKNOWN");
    }
    throw new ServiceException(
        "supply_outcome_unknown", "Supply withdrawal still requires reconciliation");
  }

  /** Resolves an unknown operation from server-observed container evidence and an admin decision. */
  public SupplyRefreshResult resolveUnknown(
      String operationId, long resolvedBy, UnknownResolution resolution, int removedQuantity) {
    recoverStaleOperations();
    SupplyOperation operation = readOperation(normalizeOperationId(operationId));
    if (!"UNKNOWN".equals(operation.state())) {
      throw new ServiceException("supply_not_unknown", "Supply operation is not unknown");
    }
    SupplyListing listing = readListing(operation.listingId());
    SupplySnapshot observed = snapshot(listing.location());
    int observedQuantity = envelopeQuantity(observed, listing.item());
    if (resolution == UnknownResolution.NOT_APPLIED) {
      if (observed.version() != operation.expectedVersion()
          || observedQuantity != operation.expectedItemQuantity()) {
        throw new ServiceException(
            "supply_reconciliation_conflict", "Container no longer matches pre-withdraw evidence");
      }
      recordResolution(operation, resolvedBy, resolution, observed, observedQuantity, 0);
      SupplyListing current = readListing(operation.listingId());
      return new SupplyRefreshResult(
          current.id(), 0, current.currentStock(), current.maximumStock(),
          current.loadedTotal(), current.soldTotal(), current.status(), operation.operationId());
    }
    if (removedQuantity < 1 || removedQuantity > operation.requestedQuantity()
        || observed.version() == operation.expectedVersion()
        || observedQuantity != operation.expectedItemQuantity() - removedQuantity) {
      throw new ServiceException(
          "supply_reconciliation_conflict", "Container evidence does not prove the removed quantity");
    }
    return complete(
        listing, operation.operationId(),
        new SupplyWithdrawal(observed.version(), withCount(listing.item(), removedQuantity),
            removedQuantity),
        "UNKNOWN",
        new ResolutionEvidence(resolvedBy, resolution, observed.version(), observedQuantity));
  }

  public SupplyInfo info(long listingId) {
    SupplyListing listing = readListing(listingId);
    return new SupplyInfo(
        listing.sourceMode(), listing.location().world(), listing.location().x(),
        listing.location().y(), listing.location().z(), listing.batchSize(),
        listing.maximumStock(), listing.accessProtected(), listing.loadedTotal(),
        listing.soldTotal());
  }

  public List<UnknownOperationView> unknownOperations(int requestedLimit) {
    int limit = Math.max(1, Math.min(requestedLimit, 200));
    recoverStaleOperations();
    return database.withConnection(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT o.operation_id,o.listing_id,o.requested_by,u.username,o.expected_version,"
              + "o.requested_quantity,e.expected_item_quantity,o.error_message,"
              + "o.created_at,o.updated_at FROM market_supply_operations o "
              + "JOIN web_users u ON u.id=o.requested_by "
              + "JOIN market_supply_operation_evidence e ON e.operation_id=o.operation_id "
              + "WHERE o.state='UNKNOWN' ORDER BY o.updated_at ASC LIMIT ?")) {
        statement.setInt(1, limit);
        try (ResultSet result = statement.executeQuery()) {
          java.util.ArrayList<UnknownOperationView> values = new java.util.ArrayList<>();
          while (result.next()) {
            values.add(new UnknownOperationView(
                result.getString(1), result.getLong(2), result.getLong(3), result.getString(4),
                result.getString(5), result.getInt(6), result.getInt(7), result.getString(8),
                result.getTimestamp(9).toInstant(), result.getTimestamp(10).toInstant()));
          }
          return List.copyOf(values);
        }
      }
    });
  }

  private SupplyRefreshResult complete(
      SupplyListing expected, String operationId, SupplyWithdrawal withdrawal) {
    return complete(expected, operationId, withdrawal, "PENDING", null);
  }

  private SupplyRefreshResult complete(
      SupplyListing expected,
      String operationId,
      SupplyWithdrawal withdrawal,
      String expectedState) {
    return complete(expected, operationId, withdrawal, expectedState, null);
  }

  private SupplyRefreshResult complete(
      SupplyListing expected,
      String operationId,
      SupplyWithdrawal withdrawal,
      String expectedState,
      ResolutionEvidence resolution) {
    if (withdrawal.removedQuantity() < 0
        || withdrawal.removedQuantity() > expected.batchSize()
        || (withdrawal.removedQuantity() > 0
            && !sameEnvelopeType(withdrawal.removed(), expected.item()))) {
      markUnknown(operationId, "native supply result did not match the listing template");
      throw new ServiceException(
          "supply_outcome_unknown", "Supply withdrawal requires reconciliation");
    }
    return database.inTransaction(
        connection -> {
          SupplyListing current = readListing(connection, expected.id(), true);
          int room = Math.max(0, current.maximumStock() - current.currentStock());
          if (withdrawal.removedQuantity() > room) {
            throw new ServiceException(
                "supply_outcome_unknown", "Supply stock changed after native withdrawal");
          }
          int stock = current.currentStock() + withdrawal.removedQuantity();
          long loaded = current.loadedTotal() + withdrawal.removedQuantity();
          String status = stock > 0 && "SUPPLY_EMPTY".equals(current.status())
              ? "ACTIVE" : current.status();
          try (PreparedStatement statement = connection.prepareStatement(
              "UPDATE market_listings SET quantity=?,status=?,supply_loaded_total=?,"
                  + "supply_last_loaded_amount=?,supply_last_loaded_at=CURRENT_TIMESTAMP "
                  + "WHERE id=? AND source_mode='SUPPLY'")) {
            statement.setInt(1, stock);
            statement.setString(2, status);
            statement.setLong(3, loaded);
            statement.setInt(4, withdrawal.removedQuantity());
            statement.setLong(5, current.id());
            if (statement.executeUpdate() != 1) {
              throw new ServiceException("supply_conflict", "Supply listing changed concurrently");
            }
          }
          SupplyRefreshResult result = new SupplyRefreshResult(
              current.id(), withdrawal.removedQuantity(), stock, current.maximumStock(),
              loaded, current.soldTotal(), status, operationId);
          try (PreparedStatement statement = connection.prepareStatement(
              "UPDATE market_supply_operations SET state='SUCCESS',removed_quantity=?,"
                  + "result_json=?,updated_at=CURRENT_TIMESTAMP WHERE operation_id=? AND state=?")) {
            statement.setInt(1, withdrawal.removedQuantity());
            statement.setString(2, gson.toJson(result));
            statement.setString(3, operationId);
            statement.setString(4, expectedState);
            if (statement.executeUpdate() != 1) {
              throw new ServiceException(
                  "supply_outcome_unknown", "Supply operation journal changed unexpectedly");
            }
          }
          if (resolution != null) {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE market_supply_operation_evidence SET observed_version=?,"
                    + "observed_item_quantity=?,resolution=?,resolved_by=?,"
                    + "resolved_at=CURRENT_TIMESTAMP WHERE operation_id=? AND resolution IS NULL")) {
              statement.setString(1, Long.toUnsignedString(resolution.observedVersion()));
              statement.setInt(2, resolution.observedItemQuantity());
              statement.setString(3, resolution.resolution().name());
              statement.setLong(4, resolution.resolvedBy());
              statement.setString(5, operationId);
              if (statement.executeUpdate() != 1) {
                throw new ServiceException(
                    "supply_reconciliation_conflict", "Supply reconciliation was already recorded");
              }
            }
          }
          deleteLease(connection, current.id(), operationId);
          return result;
        });
  }

  private SupplyRefreshResult acquire(
      SupplyListing listing,
      long requestedBy,
      String operationId,
      SupplySnapshot snapshot,
      int requested) {
    try {
      database.inTransaction(
          connection -> {
            try (PreparedStatement expired = connection.prepareStatement(
                "DELETE FROM market_supply_leases WHERE lease_until<CURRENT_TIMESTAMP")) {
              expired.executeUpdate();
            }
            try (PreparedStatement unresolved = connection.prepareStatement(
                "SELECT operation_id FROM market_supply_operations"
                    + " WHERE listing_id=? AND state='UNKNOWN' LIMIT 1")) {
              unresolved.setLong(1, listing.id());
              try (ResultSet result = unresolved.executeQuery()) {
                if (result.next()) {
                  throw new ServiceException(
                      "supply_outcome_unknown", "A prior supply withdrawal requires reconciliation");
                }
              }
            }
            try (PreparedStatement lease = connection.prepareStatement(
                    "INSERT INTO market_supply_leases"
                        + " (listing_id,operation_id,owner_server,lease_until) VALUES (?,?,?,?)");
                PreparedStatement operation = connection.prepareStatement(
                    "INSERT INTO market_supply_operations"
                        + " (operation_id,listing_id,requested_by,state,expected_version,"
                        + "expected_hash,requested_quantity) VALUES (?,?,?,'PENDING',?,?,?)")) {
              lease.setLong(1, listing.id());
              lease.setString(2, operationId);
              lease.setString(3, serverId);
              lease.setTimestamp(4, java.sql.Timestamp.from(Instant.now().plus(LEASE_DURATION)));
              lease.executeUpdate();
              operation.setString(1, operationId);
              operation.setLong(2, listing.id());
              operation.setLong(3, requestedBy);
              operation.setString(4, Long.toUnsignedString(snapshot.version()));
              operation.setString(5, listing.item().payloadHash());
              operation.setInt(6, requested);
              operation.executeUpdate();
            }
            try (PreparedStatement evidence = connection.prepareStatement(
                "INSERT INTO market_supply_operation_evidence"
                    + " (operation_id,expected_item_quantity) VALUES (?,?)")) {
              evidence.setString(1, operationId);
              evidence.setInt(2, envelopeQuantity(snapshot, listing.item()));
              evidence.executeUpdate();
            }
            return null;
          });
    } catch (RuntimeException conflict) {
      SupplyRefreshResult replay = replay(operationId, listing.id(), requestedBy);
      if (replay != null) return replay;
      if (conflict instanceof ServiceException service
          && "supply_outcome_unknown".equals(service.code())) {
        throw service;
      }
      throw new ServiceException("supply_refresh_busy", "Another supply refresh is in progress");
    }
    return null;
  }

  private static boolean sameEnvelopeType(ItemEnvelope actual, ItemEnvelope expected) {
    return actual != null
        && actual.registryId().equals(expected.registryId())
        && actual.codec().equals(expected.codec())
        && actual.codecVersion() == expected.codecVersion()
        && actual.compatibilityDomain().equals(expected.compatibilityDomain());
  }

  private static int envelopeQuantity(SupplySnapshot snapshot, ItemEnvelope expected) {
    return snapshot.items().stream()
        .filter(item -> sameEnvelopeType(item, expected))
        .mapToInt(ItemEnvelope::count)
        .sum();
  }

  private static ItemEnvelope withCount(ItemEnvelope source, int count) {
    return new ItemEnvelope(
        source.schemaVersion(), source.codec(), source.codecVersion(),
        source.compatibilityDomain(), source.registryId(), count,
        source.payloadEncoding(), source.payload(), source.payloadHash(),
        source.summary(), source.createdAt());
  }

  private SupplySnapshot snapshot(SupplyLocation location) {
    PlatformResult<SupplySnapshot> result;
    try {
      result = gateway.snapshot(location).toCompletableFuture()
          .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new ServiceException("supply_unavailable", "Supply container is unavailable");
    }
    if (result instanceof PlatformResult.Success<SupplySnapshot> success) return success.value();
    throw new ServiceException("supply_unavailable", "Supply container is unavailable");
  }

  private SupplyRefreshResult replay(String operationId, long listingId, long requestedBy) {
    return database.withConnection(
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(
              "SELECT listing_id,requested_by,state,result_json FROM market_supply_operations "
                  + "WHERE operation_id=?")) {
            statement.setString(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) return null;
              if (result.getLong(1) != listingId || result.getLong(2) != requestedBy) {
                throw new ServiceException(
                    "idempotency_key_conflict", "Supply operation id belongs to another request");
              }
              return switch (result.getString(3)) {
                case "SUCCESS" -> gson.fromJson(result.getString(4), SupplyRefreshResult.class);
                case "UNKNOWN" -> throw new ServiceException(
                    "supply_outcome_unknown", "Supply withdrawal requires reconciliation");
                case "PENDING" -> throw new ServiceException(
                    "supply_refresh_busy", "Supply refresh is still in progress");
                default -> null;
              };
            }
          }
        });
  }

  private SupplyOperation readOperation(String operationId) {
    return database.withConnection(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT o.operation_id,o.listing_id,o.requested_by,o.state,o.expected_version,"
              + "o.requested_quantity,o.result_json,e.expected_item_quantity "
              + "FROM market_supply_operations o "
              + "LEFT JOIN market_supply_operation_evidence e ON e.operation_id=o.operation_id "
              + "WHERE o.operation_id=?")) {
        statement.setString(1, operationId);
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next()) {
            throw new ServiceException("supply_operation_not_found", "Supply operation was not found");
          }
          if (result.getObject(8) == null) {
            throw new ServiceException(
                "supply_evidence_missing", "Supply operation predates durable reconciliation evidence");
          }
          String resultJson = result.getString(7);
          String expectedVersion = result.getString(5);
          if (expectedVersion != null && expectedVersion.startsWith("paper:")) {
            throw new ServiceException(
                "supply_compatibility_domain",
                "This supply operation must be reconciled on its Paper node");
          }
          return new SupplyOperation(
              result.getString(1), result.getLong(2), result.getLong(3), result.getString(4),
              Long.parseUnsignedLong(expectedVersion), result.getInt(6), result.getInt(8),
              resultJson == null || resultJson.isBlank()
                  ? null : gson.fromJson(resultJson, SupplyRefreshResult.class));
        }
      }
    });
  }

  private void recordResolution(
      SupplyOperation operation,
      long resolvedBy,
      UnknownResolution resolution,
      SupplySnapshot observed,
      int observedQuantity,
      int removedQuantity) {
    if (resolution != UnknownResolution.NOT_APPLIED || removedQuantity != 0) {
      throw new IllegalArgumentException("Only a not-applied resolution is recorded separately");
    }
    database.inTransaction(connection -> {
      try (PreparedStatement evidence = connection.prepareStatement(
          "UPDATE market_supply_operation_evidence SET observed_version=?,"
              + "observed_item_quantity=?,resolution=?,resolved_by=?,"
              + "resolved_at=CURRENT_TIMESTAMP WHERE operation_id=? AND resolution IS NULL")) {
        evidence.setString(1, Long.toUnsignedString(observed.version()));
        evidence.setInt(2, observedQuantity);
        evidence.setString(3, resolution.name());
        evidence.setLong(4, resolvedBy);
        evidence.setString(5, operation.operationId());
        if (evidence.executeUpdate() != 1) {
          throw new ServiceException(
              "supply_reconciliation_conflict", "Supply reconciliation was already recorded");
        }
      }
      try (PreparedStatement journal = connection.prepareStatement(
          "UPDATE market_supply_operations SET state='FAILED',removed_quantity=0,"
              + "error_message='admin confirmed not applied',updated_at=CURRENT_TIMESTAMP "
              + "WHERE operation_id=? AND state='UNKNOWN'")) {
        journal.setString(1, operation.operationId());
        if (journal.executeUpdate() != 1) {
          throw new ServiceException(
              "supply_reconciliation_conflict", "Supply operation changed during reconciliation");
        }
      }
      deleteLease(connection, operation.listingId(), operation.operationId());
      return null;
    });
  }

  private SupplyListing readListing(long listingId) {
    return database.withConnection(connection -> readListing(connection, listingId, false));
  }

  private SupplyListing readListing(
      java.sql.Connection connection, long listingId, boolean lock) throws java.sql.SQLException {
    String sql = "SELECT id,seller_user_id,source_mode,supply_world,supply_x,supply_y,supply_z,"
        + "supply_batch_size,supply_max_stock,supply_access_protected,supply_loaded_total,"
        + "supply_sold_total,quantity,status,raw_item_blob FROM market_listings WHERE id=?"
        + (lock && !database.dbType().isSqlite() ? " FOR UPDATE" : "");
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, listingId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new ServiceException("not_found", "Market listing was not found");
        if (!"SUPPLY".equals(result.getString("source_mode"))) {
          throw new ServiceException("supply_not_configured", "Listing is not a supply listing");
        }
        return new SupplyListing(
            result.getLong("id"), result.getLong("seller_user_id"), result.getString("source_mode"),
            new SupplyLocation(result.getString("supply_world"), result.getInt("supply_x"),
                result.getInt("supply_y"), result.getInt("supply_z")),
            Math.max(1, result.getInt("supply_batch_size")),
            Math.max(1, result.getInt("supply_max_stock")),
            result.getBoolean("supply_access_protected"),
            result.getLong("supply_loaded_total"), result.getLong("supply_sold_total"),
            result.getInt("quantity"), result.getString("status"),
            envelopes.decode(result.getBytes("raw_item_blob")));
      }
    }
  }

  private void markUnknown(String operationId, String error) {
    database.inTransaction(
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(
              "UPDATE market_supply_operations SET state='UNKNOWN',error_message=?,"
                  + "updated_at=CURRENT_TIMESTAMP WHERE operation_id=? AND state='PENDING'")) {
            statement.setString(1, error);
            statement.setString(2, operationId);
            statement.executeUpdate();
          }
          return null;
        });
  }

  private void recoverStaleOperations() {
    database.inTransaction(connection -> {
      try (PreparedStatement stale = connection.prepareStatement(
          "UPDATE market_supply_operations SET state='UNKNOWN',"
              + "error_message='supply lease expired before a durable outcome was recorded',"
              + "updated_at=CURRENT_TIMESTAMP WHERE state='PENDING' AND listing_id IN "
              + "(SELECT listing_id FROM market_supply_leases WHERE lease_until<CURRENT_TIMESTAMP)")) {
        stale.executeUpdate();
      }
      try (PreparedStatement expired = connection.prepareStatement(
          "DELETE FROM market_supply_leases WHERE lease_until<CURRENT_TIMESTAMP")) {
        expired.executeUpdate();
      }
      return null;
    });
  }

  private void releaseFailed(String operationId, String error) {
    database.inTransaction(
        connection -> {
          long listingId = -1;
          try (PreparedStatement select = connection.prepareStatement(
              "SELECT listing_id FROM market_supply_operations WHERE operation_id=?")) {
            select.setString(1, operationId);
            try (ResultSet result = select.executeQuery()) {
              if (result.next()) listingId = result.getLong(1);
            }
          }
          try (PreparedStatement statement = connection.prepareStatement(
              "UPDATE market_supply_operations SET state='FAILED',error_message=?,"
                  + "updated_at=CURRENT_TIMESTAMP WHERE operation_id=? AND state='PENDING'")) {
            statement.setString(1, error);
            statement.setString(2, operationId);
            statement.executeUpdate();
          }
          if (listingId > 0) deleteLease(connection, listingId, operationId);
          return null;
        });
  }

  private static void deleteLease(
      java.sql.Connection connection, long listingId, String operationId) throws java.sql.SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "DELETE FROM market_supply_leases WHERE listing_id=? AND operation_id=?")) {
      statement.setLong(1, listingId);
      statement.setString(2, operationId);
      statement.executeUpdate();
    }
  }

  private void ensureSchema() {
    database.inTransaction(
        connection -> {
          try (PreparedStatement operations = connection.prepareStatement(
                  "CREATE TABLE IF NOT EXISTS market_supply_operations ("
                      + "operation_id VARCHAR(128) PRIMARY KEY,listing_id BIGINT NOT NULL,"
                      + "requested_by BIGINT NOT NULL,state VARCHAR(16) NOT NULL,"
                      + "expected_version VARCHAR(32) NOT NULL,expected_hash VARCHAR(96) NOT NULL,"
                      + "requested_quantity INT NOT NULL,removed_quantity INT NULL,"
                      + "result_json TEXT NULL,error_message VARCHAR(500) NULL,"
                      + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                      + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
              PreparedStatement leases = connection.prepareStatement(
                  "CREATE TABLE IF NOT EXISTS market_supply_leases ("
                      + "listing_id BIGINT PRIMARY KEY,operation_id VARCHAR(128) NOT NULL,"
                      + "owner_server VARCHAR(128) NOT NULL,lease_until TIMESTAMP NOT NULL)");
              PreparedStatement evidence = connection.prepareStatement(
                  "CREATE TABLE IF NOT EXISTS market_supply_operation_evidence ("
                      + "operation_id VARCHAR(128) PRIMARY KEY,"
                      + "expected_item_quantity INT NOT NULL,observed_version VARCHAR(32) NULL,"
                      + "observed_item_quantity INT NULL,resolution VARCHAR(32) NULL,"
                      + "resolved_by BIGINT NULL,resolved_at TIMESTAMP NULL)")) {
            operations.execute();
            leases.execute();
            evidence.execute();
          }
          return null;
        });
  }

  private static String normalizeOperationId(String value) {
    String normalized = value.trim();
    if (normalized.length() > 128 || !normalized.matches("[A-Za-z0-9._:-]+")) {
      throw new ServiceException("invalid_idempotency_key", "Supply operation id is invalid");
    }
    return normalized;
  }

  private static String defaultOperationId(SupplyListing listing, long version) {
    return "supply:" + listing.id() + ":" + listing.currentStock() + ":"
        + listing.loadedTotal() + ":" + Long.toUnsignedString(version);
  }

  private static String platformMessage(PlatformResult<?> result) {
    if (result instanceof PlatformResult.Rejected<?> rejected) return rejected.messageKey();
    if (result instanceof PlatformResult.Unavailable<?> unavailable) return unavailable.reason();
    if (result instanceof PlatformResult.Conflict<?> conflict) return conflict.currentState();
    return "native supply operation failed";
  }

  public record SupplyRefreshResult(
      long listingId, int loadedAmount, int currentStock, int maxStock,
      long loadedTotal, long soldTotal, String status, String operationId) {}

  public enum UnknownResolution {
    APPLIED,
    NOT_APPLIED
  }

  public record UnknownOperationView(
      String operationId,
      long listingId,
      long requestedBy,
      String username,
      String expectedVersion,
      int requestedQuantity,
      int expectedItemQuantity,
      String error,
      Instant createdAt,
      Instant updatedAt) {}

  public record SupplyInfo(
      String sourceMode, String world, int x, int y, int z, int batchSize,
      int maxStock, boolean accessProtected, long loadedTotal, long soldTotal) {}

  public record SupplyInspection(long version, List<ItemEnvelope> items) {
    public SupplyInspection {
      items = List.copyOf(items);
    }
  }

  public record SupplyCreateRequest(
      long ownerUserId,
      UUID ownerId,
      CurrencyType currency,
      long price,
      SupplyLocation location,
      String expectedPayloadHash,
      int batchSize,
      int maxStock,
      boolean accessProtected,
      String idempotencyKey,
      String remark) {
    public SupplyCreateRequest {
      Objects.requireNonNull(ownerId, "ownerId");
      Objects.requireNonNull(currency, "currency");
      Objects.requireNonNull(location, "location");
      if (expectedPayloadHash == null || expectedPayloadHash.isBlank()) {
        throw new IllegalArgumentException("expectedPayloadHash is required");
      }
      if (idempotencyKey == null || idempotencyKey.isBlank()) {
        throw new IllegalArgumentException("idempotencyKey is required");
      }
    }
  }

  public record SupplyCreateResult(
      SharedCommerceService.Listing listing, SupplyRefreshResult refresh) {}

  private record SupplyListing(
      long id, long sellerUserId, String sourceMode, SupplyLocation location,
      int batchSize, int maximumStock,
      boolean accessProtected, long loadedTotal, long soldTotal, int currentStock,
      String status, ItemEnvelope item) {}

  private record SupplyOperation(
      String operationId,
      long listingId,
      long requestedBy,
      String state,
      long expectedVersion,
      int requestedQuantity,
      int expectedItemQuantity,
      SupplyRefreshResult result) {}

  private record ResolutionEvidence(
      long resolvedBy,
      UnknownResolution resolution,
      long observedVersion,
      int observedItemQuantity) {}
}
