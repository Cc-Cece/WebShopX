package com.webshopx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Server-side persistent cart. It stores intent only and never locks price or inventory. */
class CartService {
  private final DatabaseManager databaseManager;

  CartService(DatabaseManager databaseManager) {
    this.databaseManager = databaseManager;
  }

  CartView get(long userId) {
    return databaseManager.inTransaction(connection -> {
      long cartId = ensureCart(connection, userId);
      return readCart(connection, userId, cartId);
    });
  }

  CartView add(long userId, AddLine command) {
    validateCommand(command.sourceType(), command.sourceId(), command.quantity());
    return databaseManager.inTransaction(connection -> {
      long cartId = ensureCart(connection, userId);
      lockAndCheckVersion(connection, cartId, command.expectedVersion());
      validateSource(connection, command.sourceType(), command.sourceId());
      CartLine existing = findLine(connection, cartId, command.sourceType(), command.sourceId(), command.deliveryMode());
      if (existing == null) {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO commerce_cart_lines (cart_id, source_type, source_id, quantity, delivery_mode, selected, source_version, metadata_json) VALUES (?, ?, ?, ?, ?, TRUE, ?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
          statement.setLong(1, cartId);
          statement.setString(2, command.sourceType().name());
          statement.setLong(3, command.sourceId());
          statement.setInt(4, command.quantity());
          statement.setString(5, normalizeDelivery(command.deliveryMode()));
          statement.setString(6, command.sourceVersion());
          statement.setString(7, command.metadataJson());
          statement.executeUpdate();
        }
      } else {
        int quantity = Math.addExact(existing.quantity(), command.quantity());
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE commerce_cart_lines SET quantity = ?, selected = TRUE, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
          statement.setInt(1, quantity);
          statement.setLong(2, existing.id());
          statement.executeUpdate();
        }
      }
      incrementVersion(connection, cartId);
      return readCart(connection, userId, cartId);
    });
  }

  CartView update(long userId, UpdateLine command) {
    if (command.lineId() <= 0 || command.quantity() != null && command.quantity() <= 0) {
      throw new ServiceException("invalid_cart_line", "Cart line input is invalid");
    }
    return databaseManager.inTransaction(connection -> {
      long cartId = ensureCart(connection, userId);
      lockAndCheckVersion(connection, cartId, command.expectedVersion());
      CartLine line = readOwnedLine(connection, cartId, command.lineId());
      String sql = "UPDATE commerce_cart_lines SET quantity = ?, selected = ?, delivery_mode = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setInt(1, command.quantity() == null ? line.quantity() : command.quantity());
        statement.setBoolean(2, command.selected() == null ? line.selected() : command.selected());
        statement.setString(3, command.deliveryMode() == null ? line.deliveryMode() : normalizeDelivery(command.deliveryMode()));
        statement.setLong(4, line.id());
        statement.executeUpdate();
      }
      incrementVersion(connection, cartId);
      return readCart(connection, userId, cartId);
    });
  }

  CartView remove(long userId, long lineId, long expectedVersion) {
    return databaseManager.inTransaction(connection -> {
      long cartId = ensureCart(connection, userId);
      lockAndCheckVersion(connection, cartId, expectedVersion);
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM commerce_cart_lines WHERE id = ? AND cart_id = ?")) {
        statement.setLong(1, lineId);
        statement.setLong(2, cartId);
        if (statement.executeUpdate() > 0) incrementVersion(connection, cartId);
      }
      return readCart(connection, userId, cartId);
    });
  }

  CartView clear(long userId, long expectedVersion) {
    return databaseManager.inTransaction(connection -> {
      long cartId = ensureCart(connection, userId);
      lockAndCheckVersion(connection, cartId, expectedVersion);
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM commerce_cart_lines WHERE cart_id = ?")) {
        statement.setLong(1, cartId);
        if (statement.executeUpdate() > 0) incrementVersion(connection, cartId);
      }
      return readCart(connection, userId, cartId);
    });
  }

  void removePurchased(Connection connection, long cartId, List<Long> lineIds) throws SQLException {
    if (lineIds.isEmpty()) return;
    String placeholders = String.join(",", java.util.Collections.nCopies(lineIds.size(), "?"));
    try (PreparedStatement statement = connection.prepareStatement(
        "DELETE FROM commerce_cart_lines WHERE cart_id = ? AND id IN (" + placeholders + ")")) {
      statement.setLong(1, cartId);
      int index = 2;
      for (long id : lineIds) statement.setLong(index++, id);
      if (statement.executeUpdate() > 0) incrementVersion(connection, cartId);
    }
  }

  CartView readForCheckout(Connection connection, long userId, long expectedVersion) throws SQLException {
    long cartId = ensureCart(connection, userId);
    lockAndCheckVersion(connection, cartId, expectedVersion);
    return readCart(connection, userId, cartId);
  }

  private long ensureCart(Connection connection, long userId) throws SQLException {
    try (PreparedStatement select = connection.prepareStatement("SELECT id FROM commerce_carts WHERE user_id = ?")) {
      select.setLong(1, userId);
      try (ResultSet result = select.executeQuery()) {
        if (result.next()) return result.getLong(1);
      }
    }
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO commerce_carts (user_id) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
      insert.setLong(1, userId);
      insert.executeUpdate();
      try (ResultSet keys = insert.getGeneratedKeys()) {
        if (keys.next()) return keys.getLong(1);
      }
    } catch (SQLException race) {
      try (PreparedStatement select = connection.prepareStatement("SELECT id FROM commerce_carts WHERE user_id = ?")) {
        select.setLong(1, userId);
        try (ResultSet result = select.executeQuery()) {
          if (result.next()) return result.getLong(1);
        }
      }
      throw race;
    }
    throw new SQLException("Cannot create cart");
  }

  private void lockAndCheckVersion(Connection connection, long cartId, long expected) throws SQLException {
    String sql = "SELECT version FROM commerce_carts WHERE id = ?" + databaseManager.sqlProvider().forUpdateClause();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, cartId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new ServiceException("cart_missing", "Cart is missing");
        long actual = result.getLong(1);
        if (expected > 0 && actual != expected) {
          throw new ServiceException("CART_VERSION_CONFLICT", "Cart version changed: " + actual);
        }
      }
    }
  }

  private void incrementVersion(Connection connection, long cartId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "UPDATE commerce_carts SET version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setLong(1, cartId);
      statement.executeUpdate();
    }
  }

  private CartView readCart(Connection connection, long userId, long cartId) throws SQLException {
    long version;
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT version FROM commerce_carts WHERE id = ? AND user_id = ?")) {
      statement.setLong(1, cartId); statement.setLong(2, userId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new ServiceException("cart_missing", "Cart is missing");
        version = result.getLong(1);
      }
    }
    List<CartLine> lines = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT id, source_type, source_id, quantity, delivery_mode, selected, source_version, metadata_json FROM commerce_cart_lines WHERE cart_id = ? ORDER BY id")) {
      statement.setLong(1, cartId);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) lines.add(new CartLine(result.getLong("id"),
            SourceType.valueOf(result.getString("source_type")), result.getLong("source_id"),
            result.getInt("quantity"), result.getString("delivery_mode"), result.getBoolean("selected"),
            result.getString("source_version"), result.getString("metadata_json")));
      }
    }
    return new CartView(cartId, userId, version, List.copyOf(lines));
  }

  private CartLine findLine(Connection connection, long cartId, SourceType type, long sourceId,
                            String delivery) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT id, source_type, source_id, quantity, delivery_mode, selected, source_version, metadata_json FROM commerce_cart_lines WHERE cart_id = ? AND source_type = ? AND source_id = ? AND delivery_mode = ?")) {
      statement.setLong(1, cartId); statement.setString(2, type.name()); statement.setLong(3, sourceId);
      statement.setString(4, normalizeDelivery(delivery));
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? new CartLine(result.getLong("id"), type, sourceId, result.getInt("quantity"),
            result.getString("delivery_mode"), result.getBoolean("selected"), result.getString("source_version"),
            result.getString("metadata_json")) : null;
      }
    }
  }

  private CartLine readOwnedLine(Connection connection, long cartId, long lineId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT id, source_type, source_id, quantity, delivery_mode, selected, source_version, metadata_json FROM commerce_cart_lines WHERE cart_id = ? AND id = ?")) {
      statement.setLong(1, cartId); statement.setLong(2, lineId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new ServiceException("cart_line_missing", "Cart line is not visible");
        return new CartLine(lineId, SourceType.valueOf(result.getString("source_type")),
            result.getLong("source_id"), result.getInt("quantity"), result.getString("delivery_mode"),
            result.getBoolean("selected"), result.getString("source_version"), result.getString("metadata_json"));
      }
    }
  }

  private void validateSource(Connection connection, SourceType type, long id) throws SQLException {
    String sql = type == SourceType.OFFICIAL_PRODUCT
        ? "SELECT 1 FROM products WHERE id = ? AND active = TRUE AND product_type NOT LIKE 'RECYCLE%'"
        : "SELECT 1 FROM market_listings WHERE id = ? AND status = 'ACTIVE' AND market_side = 'SELL' AND trade_mode = 'DIRECT'";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new ServiceException("SOURCE_NOT_CARTABLE", "Source cannot be added to cart");
      }
    }
  }

  private void validateCommand(SourceType type, long id, int quantity) {
    if (type == null || id <= 0 || quantity <= 0 || quantity > 64) {
      throw new ServiceException("invalid_cart_line", "Cart line input is invalid");
    }
  }

  private String normalizeDelivery(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  enum SourceType { OFFICIAL_PRODUCT, MARKET_LISTING }
  record AddLine(SourceType sourceType, long sourceId, int quantity, String deliveryMode,
                 long expectedVersion, String sourceVersion, String metadataJson) {}
  record UpdateLine(long lineId, Integer quantity, Boolean selected, String deliveryMode, long expectedVersion) {}
  record CartLine(long id, SourceType sourceType, long sourceId, int quantity, String deliveryMode,
                  boolean selected, String sourceVersion, String metadataJson) {}
  record CartView(long id, long userId, long version, List<CartLine> lines) {}
}
