package com.webshopx;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

class OrderService {
  private final DatabaseManager databaseManager;
  private final ProductService productService;
  private final WalletService walletService;
  private final SecureRandom secureRandom;

  OrderService(
      DatabaseManager databaseManager,
      ProductService productService,
      WalletService walletService) {
    this.databaseManager = databaseManager;
    this.productService = productService;
    this.walletService = walletService;
    this.secureRandom = new SecureRandom();
  }

  OrderPlacementResult placeOrder(long userId, long productId, int quantity, String idempotencyKey) {
    if (quantity < 1 || quantity > 64) {
      throw new ServiceException("invalid_quantity", "Quantity must be between 1 and 64");
    }
    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);

    return databaseManager.inTransaction(connection ->
        placeOrderInTransaction(connection, userId, productId, quantity, normalizedKey));
  }

  private OrderPlacementResult placeOrderInTransaction(
      Connection connection,
      long userId,
      long productId,
      int quantity,
      String idempotencyKey) throws SQLException {
    ExistingOrder existingOrder = readExistingOrder(connection, userId, idempotencyKey);
    if (existingOrder != null) {
      return new OrderPlacementResult(
          PlacementState.EXISTING,
          existingOrder.orderNo(),
          CurrencyType.valueOf(existingOrder.currency()),
          existingOrder.totalAmount());
    }

    UUID playerUuid = readBoundUuidForUpdate(connection, userId);
    ProductService.ProductView product = productService.readActiveProduct(connection, productId, true);
    long totalAmount = Math.multiplyExact(product.price(), quantity);

    String orderNo = newOrderNo();
    walletService.applyDelta(
        connection,
        userId,
        product.currency(),
        -totalAmount,
        "ORDER_DEBIT",
        orderNo,
        true);

    long orderId = insertOrder(connection, orderNo, userId, playerUuid, product.currency(),
        totalAmount, idempotencyKey);
    long itemId = insertOrderItem(connection, orderId, product.id(), quantity, product.price());
    insertDelivery(connection, orderId, itemId, playerUuid, product.commandTemplate(), quantity);

    return new OrderPlacementResult(PlacementState.CREATED, orderNo, product.currency(), totalAmount);
  }

  private ExistingOrder readExistingOrder(Connection connection, long userId, String idempotencyKey)
      throws SQLException {
    String sql = """
        SELECT order_no, currency, total_amount
        FROM orders
        WHERE user_id = ? AND idempotency_key = ?
        FOR UPDATE
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      statement.setString(2, idempotencyKey);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return new ExistingOrder(
            resultSet.getString("order_no"),
            resultSet.getString("currency"),
            resultSet.getLong("total_amount"));
      }
    }
  }

  private UUID readBoundUuidForUpdate(Connection connection, long userId) throws SQLException {
    String sql = "SELECT bound_uuid FROM web_users WHERE id = ? FOR UPDATE";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("user_missing", "User not found");
        }
        String rawUuid = resultSet.getString("bound_uuid");
        if (rawUuid == null || rawUuid.isBlank()) {
          throw new ServiceException("not_bound", "Minecraft account is not bound yet");
        }
        return UUID.fromString(rawUuid);
      }
    }
  }

  private long insertOrder(
      Connection connection,
      String orderNo,
      long userId,
      UUID playerUuid,
      CurrencyType currency,
      long totalAmount,
      String idempotencyKey) throws SQLException {
    String sql = """
        INSERT INTO orders (order_no, user_id, mc_uuid, currency, total_amount, status, idempotency_key)
        VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
        """;
    try (PreparedStatement statement =
             connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, orderNo);
      statement.setLong(2, userId);
      statement.setString(3, playerUuid.toString());
      statement.setString(4, currency.name());
      statement.setLong(5, totalAmount);
      statement.setString(6, idempotencyKey);
      statement.executeUpdate();
      try (ResultSet keyResult = statement.getGeneratedKeys()) {
        if (!keyResult.next()) {
          throw new IllegalStateException("Failed to read inserted order id");
        }
        return keyResult.getLong(1);
      }
    }
  }

  private long insertOrderItem(
      Connection connection,
      long orderId,
      long productId,
      int quantity,
      long unitPrice) throws SQLException {
    String sql = """
        INSERT INTO order_items (order_id, product_id, quantity, unit_price)
        VALUES (?, ?, ?, ?)
        """;
    try (PreparedStatement statement =
             connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, orderId);
      statement.setLong(2, productId);
      statement.setInt(3, quantity);
      statement.setLong(4, unitPrice);
      statement.executeUpdate();
      try (ResultSet keyResult = statement.getGeneratedKeys()) {
        if (!keyResult.next()) {
          throw new IllegalStateException("Failed to read inserted order item id");
        }
        return keyResult.getLong(1);
      }
    }
  }

  private void insertDelivery(
      Connection connection,
      long orderId,
      long itemId,
      UUID playerUuid,
      String commandText,
      int quantity) throws SQLException {
    String sql = """
        INSERT INTO delivery_queue (order_id, item_id, mc_uuid, command_text, quantity, status, next_retry_at)
        VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, orderId);
      statement.setLong(2, itemId);
      statement.setString(3, playerUuid.toString());
      statement.setString(4, commandText);
      statement.setInt(5, quantity);
      statement.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now()));
      statement.executeUpdate();
    }
  }

  private String normalizeIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return newOrderNo();
    }
    String normalized = idempotencyKey.trim();
    if (normalized.length() > 96) {
      throw new ServiceException("idempotency_too_long", "Idempotency key must be <= 96 chars");
    }
    return normalized;
  }

  private String newOrderNo() {
    long timestamp = System.currentTimeMillis();
    int randomPart = secureRandom.nextInt(1_000_000);
    return String.format(Locale.ROOT, "ODR-%d-%06d", timestamp, randomPart);
  }

  private record ExistingOrder(String orderNo, String currency, long totalAmount) {
  }

  enum PlacementState {
    CREATED,
    EXISTING
  }

  record OrderPlacementResult(
      PlacementState state,
      String orderNo,
      CurrencyType currency,
      long totalAmount) {
  }
}
