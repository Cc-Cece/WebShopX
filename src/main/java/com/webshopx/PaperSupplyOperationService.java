package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Durable idempotency and crash-reconciliation journal for Paper supply withdrawals. */
final class PaperSupplyOperationService {
  private static final Duration LEASE_DURATION = Duration.ofSeconds(45);
  private final DatabaseManager database;
  private final MarketService market;
  private final Gson gson = CommerceJson.create();

  PaperSupplyOperationService(DatabaseManager database, MarketService market) {
    this.database = database;
    this.market = market;
  }

  Outcome refresh(long userId, long listingId, String operationId) {
    Prepared prepared = prepare(listingId, userId);
    String id = operationId == null || operationId.isBlank()
        ? normalize("supply-refresh:" + listingId + ":" + prepared.version())
        : normalize(operationId);
    Operation existing = read(id);
    if (existing != null) return replay(existing, userId, listingId);
    recoverStaleOperations();
    try {
      database.inTransaction(connection -> {
        try (PreparedStatement unresolved = connection.prepareStatement(
            "SELECT operation_id FROM market_supply_operations "
                + "WHERE listing_id=? AND state='UNKNOWN' LIMIT 1")) {
          unresolved.setLong(1, listingId);
          try (ResultSet result = unresolved.executeQuery()) {
            if (result.next()) {
              throw new ServiceException(
                  "supply_outcome_unknown", "A prior supply withdrawal requires reconciliation");
            }
          }
        }
        try (PreparedStatement lease = connection.prepareStatement(
            "INSERT INTO market_supply_leases "
                + "(listing_id,operation_id,owner_server,lease_until) VALUES (?,?,?,?)")) {
          lease.setLong(1, listingId);
          lease.setString(2, id);
          lease.setString(3, "paper");
          lease.setTimestamp(4, java.sql.Timestamp.from(Instant.now().plus(LEASE_DURATION)));
          lease.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO market_supply_operations "
                + "(operation_id,listing_id,requested_by,state,expected_version,expected_hash,"
                + "requested_quantity) VALUES (?,?,?,'PENDING',?,?,?)")) {
          statement.setString(1, id);
          statement.setLong(2, listingId);
          statement.setLong(3, userId);
          statement.setString(4, prepared.version());
          statement.setString(5, prepared.itemHash());
          statement.setInt(6, prepared.requestedQuantity());
          statement.executeUpdate();
        }
        try (PreparedStatement evidence = connection.prepareStatement(
            "INSERT INTO market_supply_operation_evidence "
                + "(operation_id,expected_item_quantity) VALUES (?,?)")) {
          evidence.setString(1, id);
          evidence.setInt(2, prepared.requestedQuantity());
          evidence.executeUpdate();
        }
        return null;
      });
    } catch (RuntimeException race) {
      Operation winner = read(id);
      if (winner != null) return replay(winner, userId, listingId);
      if (race instanceof ServiceException service
          && "supply_outcome_unknown".equals(service.code())) throw service;
      throw new ServiceException("supply_refresh_busy", "Another supply refresh is in progress");
    }
    try {
      MarketService.SupplyRefreshResult result = market.refreshSupplyListing(userId, listingId);
      Outcome outcome = new Outcome(id, result);
      complete(id, outcome);
      return outcome;
    } catch (RuntimeException failure) {
      fail(id, failure.getMessage());
      throw failure;
    }
  }

  Outcome reconcile(long userId, String operationId) {
    Operation operation = require(operationId);
    return replay(operation, userId, null);
  }

  List<UnknownView> unknown(int requestedLimit) {
    int limit = Math.max(1, Math.min(500, requestedLimit));
    recoverStaleOperations();
    return database.withConnection(connection -> {
      List<UnknownView> rows = new ArrayList<>();
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT o.operation_id,o.listing_id,o.requested_by,u.username,o.state,"
              + "o.expected_version,o.expected_hash,o.requested_quantity,o.error_message,"
              + "o.created_at,o.updated_at FROM market_supply_operations o "
              + "LEFT JOIN web_users u ON u.id=o.requested_by "
              + "WHERE o.state IN ('PENDING','UNKNOWN') ORDER BY o.created_at LIMIT ?")) {
        statement.setInt(1, limit);
        try (ResultSet result = statement.executeQuery()) {
          while (result.next()) {
            rows.add(new UnknownView(
                result.getString("operation_id"), result.getLong("listing_id"),
                result.getLong("requested_by"), result.getString("username"),
                result.getString("state"), result.getString("expected_version"),
                result.getString("expected_hash"), result.getInt("requested_quantity"),
                result.getString("error_message"), result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant()));
          }
        }
      }
      return List.copyOf(rows);
    });
  }

  Outcome resolve(String operationId, long resolvedBy, Resolution resolution, int removedQuantity) {
    Operation operation = require(operationId);
    if (!"PENDING".equals(operation.state()) && !"UNKNOWN".equals(operation.state())) {
      throw new ServiceException("supply_not_unknown", "Supply operation is not unresolved");
    }
    if (resolution == Resolution.NOT_APPLIED) {
      database.inTransaction(connection -> {
        recordResolution(connection, operation.id(), resolvedBy, resolution, 0);
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE market_supply_operations SET state='FAILED',removed_quantity=0,"
                + "error_message='admin confirmed not applied',updated_at=CURRENT_TIMESTAMP "
                + "WHERE operation_id=? AND state IN ('PENDING','UNKNOWN')")) {
          statement.setString(1, operation.id());
          if (statement.executeUpdate() != 1) throw conflict();
        }
        deleteLease(connection, operation.listingId(), operation.id());
        return null;
      });
      return new Outcome(operation.id(), null);
    }
    if (removedQuantity < 1 || removedQuantity > operation.requestedQuantity()) {
      throw new ServiceException(
          "invalid_reconciliation", "Removed quantity exceeds the journaled request");
    }
    return database.inTransaction(connection -> {
      Listing listing = readListing(connection, operation.listingId(), true);
      long expectedLoaded = expectedLoaded(operation.expectedVersion());
      boolean alreadyApplied = listing.loadedTotal() == expectedLoaded + removedQuantity;
      if (listing.loadedTotal() != expectedLoaded && !alreadyApplied) {
        throw new ServiceException(
            "supply_reconciliation_conflict", "Supply listing changed after the unknown operation");
      }
      if (!alreadyApplied && listing.currentStock() + removedQuantity > listing.maximumStock()) {
        throw new ServiceException(
            "supply_reconciliation_conflict", "Reconciled stock would exceed its maximum");
      }
      int current = alreadyApplied
          ? listing.currentStock() : listing.currentStock() + removedQuantity;
      long loaded = alreadyApplied
          ? listing.loadedTotal() : listing.loadedTotal() + removedQuantity;
      String status = current > 0 ? "ACTIVE" : listing.status();
      if (!alreadyApplied) {
        try (PreparedStatement update = connection.prepareStatement(
            "UPDATE market_listings SET quantity=?,quantity_total=?,supply_loaded_total=?,"
                + "supply_last_loaded_amount=?,supply_last_loaded_at=CURRENT_TIMESTAMP,status=? "
                + "WHERE id=?")) {
          update.setInt(1, current);
          update.setInt(2, listing.maximumStock());
          update.setLong(3, loaded);
          update.setInt(4, removedQuantity);
          update.setString(5, status);
          update.setLong(6, listing.id());
          if (update.executeUpdate() != 1) throw conflict();
        }
      }
      MarketService.SupplyRefreshResult result = new MarketService.SupplyRefreshResult(
          listing.id(), removedQuantity, current, listing.maximumStock(), loaded,
          listing.soldTotal(), status);
      Outcome outcome = new Outcome(operation.id(), result);
      recordResolution(connection, operation.id(), resolvedBy, resolution, removedQuantity);
      try (PreparedStatement journal = connection.prepareStatement(
          "UPDATE market_supply_operations SET state='SUCCESS',removed_quantity=?,result_json=?,"
              + "error_message=NULL,updated_at=CURRENT_TIMESTAMP "
              + "WHERE operation_id=? AND state IN ('PENDING','UNKNOWN')")) {
        journal.setInt(1, removedQuantity);
        journal.setString(2, journalJson(outcome));
        journal.setString(3, operation.id());
        if (journal.executeUpdate() != 1) throw conflict();
      }
      deleteLease(connection, operation.listingId(), operation.id());
      return outcome;
    });
  }

  private Prepared prepare(long listingId, long userId) {
    return database.withConnection(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT seller_user_id,quantity,supply_batch_size,supply_max_stock,"
              + "supply_loaded_total,item_hash,source_mode FROM market_listings WHERE id=?")) {
        statement.setLong(1, listingId);
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next()) throw new ServiceException("listing_not_found", "Listing was not found");
          if (result.getLong("seller_user_id") != userId) {
            throw new ServiceException("forbidden", "Only the supply listing owner can refresh it");
          }
          if (!"SUPPLY".equalsIgnoreCase(result.getString("source_mode"))) {
            throw new ServiceException("supply_not_configured", "Listing is not a supply listing");
          }
          int current = result.getInt("quantity");
          int maximum = Math.max(1, result.getInt("supply_max_stock"));
          int batch = Math.max(1, result.getInt("supply_batch_size"));
          int requested = Math.max(0, Math.min(batch, maximum - current));
          return new Prepared(
              "paper:" + result.getLong("supply_loaded_total"),
              String.valueOf(result.getString("item_hash")), requested);
        }
      }
    });
  }

  private Listing readListing(java.sql.Connection connection, long id, boolean lock)
      throws SQLException {
    String suffix = lock && !database.dbType().isSqlite() ? " FOR UPDATE" : "";
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT id,quantity,supply_max_stock,supply_loaded_total,supply_sold_total,status "
            + "FROM market_listings WHERE id=?" + suffix)) {
      statement.setLong(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new ServiceException("listing_not_found", "Listing was not found");
        return new Listing(
            result.getLong("id"), result.getInt("quantity"),
            Math.max(1, result.getInt("supply_max_stock")),
            result.getLong("supply_loaded_total"), result.getLong("supply_sold_total"),
            result.getString("status"));
      }
    }
  }

  private Operation require(String operationId) {
    Operation operation = read(normalize(operationId));
    if (operation == null) {
      throw new ServiceException("supply_operation_not_found", "Supply operation was not found");
    }
    return operation;
  }

  private Operation read(String id) {
    return database.withConnection(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT operation_id,listing_id,requested_by,state,expected_version,requested_quantity,"
              + "result_json,error_message "
              + "FROM market_supply_operations WHERE operation_id=?")) {
        statement.setString(1, id);
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next()) return null;
          return new Operation(
              result.getString("operation_id"), result.getLong("listing_id"),
              result.getLong("requested_by"), result.getString("state"),
              result.getString("expected_version"), result.getInt("requested_quantity"),
              result.getString("result_json"),
              result.getString("error_message"));
        }
      }
    });
  }

  private Outcome replay(Operation operation, long userId, Long listingId) {
    if (operation.requestedBy() != userId) {
      throw new ServiceException("forbidden", "Supply operation belongs to another user");
    }
    if (listingId != null && operation.listingId() != listingId) {
      throw new ServiceException(
          "idempotency_key_conflict", "Supply operation id belongs to another request");
    }
    if ("SUCCESS".equals(operation.state()) && operation.resultJson() != null) {
      JsonObject result = com.google.gson.JsonParser.parseString(operation.resultJson())
          .getAsJsonObject();
      if (result.has("result")) {
        return gson.fromJson(result, Outcome.class);
      }
      return new Outcome(
          result.get("operationId").getAsString(),
          new MarketService.SupplyRefreshResult(
              result.get("listingId").getAsLong(), result.get("loadedAmount").getAsInt(),
              result.get("currentStock").getAsInt(), result.get("maxStock").getAsInt(),
              result.get("loadedTotal").getAsLong(), result.get("soldTotal").getAsLong(),
              result.get("status").getAsString()));
    }
    if ("FAILED".equals(operation.state())) {
      throw new ServiceException(
          "supply_operation_failed",
          operation.error() == null ? "Supply withdrawal did not complete" : operation.error());
    }
    throw new ServiceException(
        "supply_outcome_unknown", "Supply withdrawal requires reconciliation");
  }

  private void complete(String id, Outcome outcome) {
    database.inTransaction(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "UPDATE market_supply_operations SET state='SUCCESS',removed_quantity=?,result_json=?,"
              + "error_message=NULL,updated_at=CURRENT_TIMESTAMP WHERE operation_id=? AND state='PENDING'")) {
        statement.setInt(1, outcome.result().loadedAmount());
        statement.setString(2, journalJson(outcome));
        statement.setString(3, id);
        if (statement.executeUpdate() != 1) throw conflict();
      }
      deleteLease(connection, -1, id);
      return null;
    });
  }

  private void fail(String id, String message) {
    database.inTransaction(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "UPDATE market_supply_operations SET state='FAILED',error_message=?,"
              + "updated_at=CURRENT_TIMESTAMP WHERE operation_id=? AND state='PENDING'")) {
        statement.setString(1, String.valueOf(message));
        statement.setString(2, id);
        statement.executeUpdate();
      }
      deleteLease(connection, -1, id);
      return null;
    });
  }

  private void recordResolution(
      java.sql.Connection connection, String id, long resolvedBy, Resolution resolution, int removed)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "UPDATE market_supply_operation_evidence SET observed_item_quantity=?,resolution=?,"
            + "resolved_by=?,resolved_at=CURRENT_TIMESTAMP WHERE operation_id=? AND resolution IS NULL")) {
      statement.setInt(1, removed);
      statement.setString(2, resolution.name());
      statement.setLong(3, resolvedBy);
      statement.setString(4, id);
      if (statement.executeUpdate() != 1) throw conflict();
    }
  }

  private static String normalize(String value) {
    String id = String.valueOf(value == null ? "" : value).trim();
    if (id.isEmpty() || id.length() > 128 || !id.matches("[A-Za-z0-9._:-]+")) {
      throw new ServiceException("invalid_idempotency_key", "Supply operation id is invalid");
    }
    return id;
  }

  private static ServiceException conflict() {
    return new ServiceException(
        "supply_reconciliation_conflict", "Supply operation changed concurrently");
  }

  private void recoverStaleOperations() {
    database.inTransaction(connection -> {
      recoverExpiredLeases(connection);
      return null;
    });
  }

  private static void recoverExpiredLeases(java.sql.Connection connection) throws SQLException {
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
  }

  private static void deleteLease(
      java.sql.Connection connection, long listingId, String operationId) throws SQLException {
    String sql = listingId > 0
        ? "DELETE FROM market_supply_leases WHERE listing_id=? AND operation_id=?"
        : "DELETE FROM market_supply_leases WHERE operation_id=?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int index = 1;
      if (listingId > 0) statement.setLong(index++, listingId);
      statement.setString(index, operationId);
      statement.executeUpdate();
    }
  }

  private static long expectedLoaded(String expectedVersion) {
    try {
      if (expectedVersion == null || !expectedVersion.startsWith("paper:")) {
        throw new ServiceException(
            "supply_compatibility_domain",
            "This supply operation must be reconciled on its Loader node");
      }
      return Long.parseLong(expectedVersion.substring("paper:".length()));
    } catch (RuntimeException malformed) {
      if (malformed instanceof ServiceException service) throw service;
      throw new ServiceException(
          "supply_evidence_missing", "Supply operation has invalid reconciliation evidence");
    }
  }

  private String journalJson(Outcome outcome) {
    JsonObject result = new JsonObject();
    result.addProperty("operationId", outcome.operationId());
    MarketService.SupplyRefreshResult value = outcome.result();
    result.addProperty("listingId", value.listingId());
    result.addProperty("loadedAmount", value.loadedAmount());
    result.addProperty("currentStock", value.currentStock());
    result.addProperty("maxStock", value.maxStock());
    result.addProperty("loadedTotal", value.loadedTotal());
    result.addProperty("soldTotal", value.soldTotal());
    result.addProperty("status", value.status());
    return gson.toJson(result);
  }

  enum Resolution { APPLIED, NOT_APPLIED }

  record Outcome(String operationId, MarketService.SupplyRefreshResult result) {}
  record UnknownView(
      String operationId, long listingId, long requestedBy, String username, String state,
      String expectedVersion, String expectedHash, int requestedQuantity, String error,
      Instant createdAt, Instant updatedAt) {}
  private record Operation(
      String id, long listingId, long requestedBy, String state,
      String expectedVersion, int requestedQuantity, String resultJson, String error) {}
  private record Prepared(String version, String itemHash, int requestedQuantity) {}
  private record Listing(
      long id, int currentStock, int maximumStock, long loadedTotal, long soldTotal, String status) {}
}
