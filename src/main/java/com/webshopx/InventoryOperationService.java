package com.webshopx;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

final class InventoryOperationService {
  private final DatabaseManager databaseManager;

  InventoryOperationService(DatabaseManager databaseManager) {
    this.databaseManager = databaseManager;
  }

  Existing find(long userId, String key) {
    validateKey(key);
    return databaseManager.withConnection(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  """
                  SELECT action, state, reference_id, result_json, error_code
                  FROM inventory_operations WHERE user_id = ? AND idempotency_key = ?
                  """)) {
            statement.setLong(1, userId);
            statement.setString(2, key.trim());
            try (ResultSet rows = statement.executeQuery()) {
              if (!rows.next()) return null;
              Object reference = rows.getObject(3);
              return new Existing(
                  rows.getString(1),
                  rows.getString(2),
                  reference instanceof Number number ? number.longValue() : null,
                  rows.getString(4),
                  rows.getString(5));
            }
          }
        });
  }

  void begin(
      long userId,
      String key,
      String action,
      int slot,
      Integer containerSlot,
      String fingerprint,
      int quantity) {
    validateKey(key);
    databaseManager.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  """
                  INSERT INTO inventory_operations (
                    user_id, idempotency_key, action, state, slot_index, container_slot,
                    item_fingerprint, quantity
                  ) VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?)
                  """)) {
            statement.setLong(1, userId);
            statement.setString(2, key.trim());
            statement.setString(3, action);
            statement.setInt(4, slot);
            statement.setObject(5, containerSlot);
            statement.setString(6, fingerprint);
            statement.setInt(7, quantity);
            statement.executeUpdate();
          }
          return null;
        });
  }

  void complete(long userId, String key, long referenceId, String resultJson) {
    update(userId, key, "SUCCESS", referenceId, resultJson, null);
  }

  void reject(long userId, String key, String errorCode) {
    update(userId, key, "REJECTED", null, null, errorCode);
  }

  private void update(
      long userId,
      String key,
      String state,
      Long referenceId,
      String resultJson,
      String errorCode) {
    databaseManager.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  """
UPDATE inventory_operations
SET state = ?, reference_id = ?, result_json = ?, error_code = ?, updated_at = CURRENT_TIMESTAMP
WHERE user_id = ? AND idempotency_key = ?
""")) {
            statement.setString(1, state);
            statement.setObject(2, referenceId);
            statement.setString(3, resultJson);
            statement.setString(4, errorCode);
            statement.setLong(5, userId);
            statement.setString(6, key.trim());
            statement.executeUpdate();
          }
          return null;
        });
  }

  private void validateKey(String key) {
    if (key == null || key.isBlank() || key.trim().length() > 96) {
      throw new ServiceException(
          "invalid_idempotency", "Idempotency key is required and must be <= 96 characters");
    }
  }

  record Existing(
      String action, String state, Long referenceId, String resultJson, String errorCode) {}
}
