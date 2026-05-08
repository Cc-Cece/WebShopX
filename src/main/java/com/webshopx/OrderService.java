package com.webshopx;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

class OrderService {
  private static final String GROUP_BUY_VOUCHER_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

  private final DatabaseManager databaseManager;
  private final SqlProvider sqlProvider;
  private final Supplier<PluginSettings> settingsSupplier;
  private final ProductService productService;
  private final WalletService walletService;
  private final PlayerPresenceService playerPresenceService;
  private final SchedulerBridge schedulerBridge;
  private final SecureRandom secureRandom;

  OrderService(
      DatabaseManager databaseManager,
      Supplier<PluginSettings> settingsSupplier,
      ProductService productService,
      WalletService walletService,
      PlayerPresenceService playerPresenceService,
      SchedulerBridge schedulerBridge) {
    this.databaseManager = databaseManager;
    this.sqlProvider = databaseManager.sqlProvider();
    this.settingsSupplier = settingsSupplier;
    this.productService = productService;
    this.walletService = walletService;
    this.playerPresenceService = playerPresenceService;
    this.schedulerBridge = schedulerBridge;
    this.secureRandom = new SecureRandom();
  }

  OrderPlacementResult placeOrder(
      long userId,
      long productId,
      int quantity,
      String idempotencyKey,
      String deliveryModeRaw) {
    ProductService.ProductView product = databaseManager.withConnection(
        connection -> productService.readActiveProduct(connection, productId, false));
    if (product.productType() == ProductService.ProductType.RECYCLE_ITEM) {
      int maxQuantity = resolveProductMaxQuantity(product);
      validatePurchaseQuantity(quantity, maxQuantity);
      return runOnPlayer(userId, () -> placeRecycleOrder(userId, product.id(), quantity, maxQuantity, idempotencyKey));
    }

    int cooldownSeconds = normalizedOrderCooldownSeconds();
    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
    return databaseManager.inTransaction(connection -> {
      ProductService.ProductView lockedProduct = productService.readActiveProduct(connection, productId, true);
      return placePurchaseOrderInTransaction(
          connection,
          userId,
          lockedProduct,
          quantity,
          normalizedKey,
          cooldownSeconds,
          deliveryModeRaw);
    });
  }

  private OrderPlacementResult placePurchaseOrderInTransaction(
      Connection connection,
      long userId,
      ProductService.ProductView product,
      int quantity,
      String idempotencyKey,
      int cooldownSeconds,
      String deliveryModeRaw) throws SQLException {
    ExistingOrder existingOrder = readExistingOrder(connection, userId, idempotencyKey);
    if (existingOrder != null) {
      int effectiveCooldown = existingOrder.refundDeadline() == null ? 0 : cooldownSeconds;
      return new OrderPlacementResult(
          PlacementState.EXISTING,
          existingOrder.orderNo(),
          CurrencyType.valueOf(existingOrder.currency()),
          existingOrder.totalAmount(),
          existingOrder.status(),
          existingOrder.refundDeadline(),
          effectiveCooldown,
          existingOrder.groupBuyVoucherCode(),
          existingOrder.groupBuyVoucherStatus(),
          existingOrder.groupBuyVoucherConsumedAt());
    }

    int maxQuantity = resolveProductMaxQuantity(product);
    validatePurchaseQuantity(quantity, maxQuantity);
    DeliveryMode deliveryMode = resolveDeliveryMode(deliveryModeRaw, product.productType());
    UUID playerUuid = readBoundUuidForUpdate(connection, userId);
    String targetServerId = resolveTargetServerId(connection, playerUuid);
    consumePersonalLimitQuota(connection, userId, product, quantity);
    long unitPrice = productService.resolveOrderUnitPrice(product);
    long totalAmount = Math.multiplyExact(unitPrice, quantity);
    String orderNo = newOrderNo();
    boolean isGroupBuyVoucher = product.productType() == ProductService.ProductType.GROUP_BUY_VOUCHER;
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime refundDeadline = !isGroupBuyVoucher
        && deliveryMode == DeliveryMode.IMMEDIATE
        && cooldownSeconds > 0
        ? now.plusSeconds(cooldownSeconds)
        : null;
    String orderStatus = isGroupBuyVoucher
        ? "DELIVERED"
        : deliveryMode == DeliveryMode.CLAIM ? "WAIT_CLAIM" : "PENDING";

    reserveProductStock(connection, product.id(), quantity);
    walletService.applyDelta(
        connection,
        userId,
        product.currency(),
        -totalAmount,
        "ORDER_DEBIT",
        orderNo,
        true);

    long orderId = insertOrder(
        connection,
        orderNo,
        userId,
        playerUuid,
        product.currency(),
        totalAmount,
        orderStatus,
        idempotencyKey,
        refundDeadline,
        targetServerId);
    long itemId = insertOrderItem(connection, orderId, product.id(), quantity, unitPrice);
    String groupBuyVoucherCode = null;
    String groupBuyVoucherStatus = null;
    LocalDateTime groupBuyVoucherConsumedAt = null;
    if (isGroupBuyVoucher) {
      groupBuyVoucherCode = insertGroupBuyVoucher(connection, orderId, userId, product.id());
      groupBuyVoucherStatus = "ISSUED";
    } else {
      productService.applyDynamicPriceEvent(
          connection,
          product,
          quantity,
          ProductService.DynamicPriceEvent.PURCHASE);
      DeliveryTaskSpec taskSpec = buildDeliveryTaskSpec(product, quantity);
      LocalDateTime deliveryAt = refundDeadline == null ? now : refundDeadline;
      insertDelivery(
          connection,
          orderId,
          itemId,
          playerUuid,
          targetServerId,
          taskSpec,
          deliveryMode,
          deliveryAt);
    }

    return new OrderPlacementResult(
        PlacementState.CREATED,
        orderNo,
        product.currency(),
        totalAmount,
        orderStatus,
        refundDeadline,
        isGroupBuyVoucher || deliveryMode == DeliveryMode.CLAIM ? 0 : cooldownSeconds,
        groupBuyVoucherCode,
        groupBuyVoucherStatus,
        groupBuyVoucherConsumedAt);
  }

  private OrderPlacementResult placeRecycleOrder(
      long userId,
      long productId,
      int quantity,
      int maxQuantity,
      String idempotencyKey) {
    int requiredAmount = quantity;
    if (requiredAmount <= 0) {
      throw new ServiceException("invalid_quantity", "Recycle amount must be positive");
    }
    if (quantity > maxQuantity) {
      throw new ServiceException("invalid_quantity", "Quantity must be between 1 and " + maxQuantity);
    }

    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);

    return databaseManager.inTransaction(connection -> {
      ExistingOrder existingOrder = readExistingOrder(connection, userId, normalizedKey);
      if (existingOrder != null) {
        return new OrderPlacementResult(
            PlacementState.EXISTING,
            existingOrder.orderNo(),
            CurrencyType.valueOf(existingOrder.currency()),
            existingOrder.totalAmount(),
            existingOrder.status(),
            existingOrder.refundDeadline(),
            0,
            existingOrder.groupBuyVoucherCode(),
            existingOrder.groupBuyVoucherStatus(),
            existingOrder.groupBuyVoucherConsumedAt());
      }

      ProductService.ProductView product = productService.readActiveProduct(connection, productId, true);
      if (product.productType() != ProductService.ProductType.RECYCLE_ITEM) {
        throw new ServiceException("invalid_product_type", "Product type is not recyclable");
      }
      if (product.itemMaterial() == null) {
        throw new ServiceException("invalid_product", "Recycle material is missing");
      }

      int resolvedMaxQuantity = resolveProductMaxQuantity(product);
      int clampedMaxQuantity = maxQuantity > 0
          ? Math.min(maxQuantity, resolvedMaxQuantity)
          : resolvedMaxQuantity;
      validatePurchaseQuantity(quantity, clampedMaxQuantity);

      UUID playerUuid = readBoundUuidForUpdate(connection, userId);
      String targetServerId = resolveTargetServerId(connection, playerUuid);
      Player player = Bukkit.getPlayer(playerUuid);
      if (player == null || !player.isOnline()) {
        throw new ServiceException("player_offline", "Player must be online for recycle orders");
      }

      Material material = Material.matchMaterial(product.itemMaterial());
      if (material == null || material == Material.AIR) {
        throw new ServiceException("invalid_product", "Recycle material is invalid");
      }
      if (!hasEnoughItem(player, material, requiredAmount)) {
        throw new ServiceException("insufficient_item", "Not enough items to recycle");
      }

      String orderNo = newOrderNo();
      long unitPrice = productService.resolveOrderUnitPrice(product);
      long totalAmount = Math.multiplyExact(unitPrice, quantity);
      boolean removed = removeItems(player, material, requiredAmount);
      if (!removed) {
        throw new ServiceException("insufficient_item", "Failed to remove recycle items");
      }

      try {
        walletService.applyDelta(
            connection,
            userId,
            product.currency(),
            totalAmount,
            "RECYCLE_CREDIT",
            orderNo,
            false);

        long orderId = insertOrder(
            connection,
            orderNo,
            userId,
            playerUuid,
            product.currency(),
            totalAmount,
            "RECYCLED",
            normalizedKey,
            null,
            targetServerId);
        insertOrderItem(connection, orderId, product.id(), quantity, unitPrice);
        productService.applyDynamicPriceEvent(
            connection,
            product,
            quantity,
            ProductService.DynamicPriceEvent.RECYCLE);
        return new OrderPlacementResult(
            PlacementState.CREATED,
            orderNo,
            product.currency(),
            totalAmount,
            "RECYCLED",
            null,
            0,
            null,
            null,
            null);
      } catch (Exception exception) {
        restoreItems(player, material, requiredAmount);
        throw exception;
      }
    });
  }

  private DeliveryTaskSpec buildDeliveryTaskSpec(ProductService.ProductView product, int quantity) {
    ProductService.ProductType productType = product.productType();
    if (productType == ProductService.ProductType.COMMAND) {
      return new DeliveryTaskSpec(
          DeliveryKind.COMMAND,
          product.commandTemplate(),
          null,
          quantity);
    }
    if (productType == ProductService.ProductType.GIVE_ITEM) {
      if (product.itemMaterial() == null) {
        throw new ServiceException("invalid_product", "Item material is missing");
      }
      int totalAmount = quantity;
      String payloadJson = "{\"material\":\""
          + product.itemMaterial()
          + "\",\"amount\":"
          + totalAmount
          + "}";
      return new DeliveryTaskSpec(
          DeliveryKind.GIVE_ITEM,
          "",
          payloadJson,
          totalAmount);
    }
    if (productType == ProductService.ProductType.POTION_EFFECT) {
      if (product.effectType() == null) {
        throw new ServiceException("invalid_product", "Potion effect type is missing");
      }
      int seconds = product.effectSeconds() == null ? 30 : product.effectSeconds();
      long totalSeconds = Math.min(86_400L, (long) seconds * (long) quantity);
      int amplifier = product.effectAmplifier() == null ? 0 : product.effectAmplifier();
      String payloadJson = "{\"effect\":\""
          + product.effectType()
          + "\",\"seconds\":"
          + totalSeconds
          + ",\"amplifier\":"
          + amplifier
          + "}";
      return new DeliveryTaskSpec(
          DeliveryKind.POTION_EFFECT,
          "",
          payloadJson,
          quantity);
    }
    throw new ServiceException("invalid_product_type", "Unsupported product type");
  }

  private int resolveProductMaxQuantity(ProductService.ProductView product) {
    Integer trackedStock = product.stockRemaining();
    Integer personalRemaining = product.personalLimitRemaining();
    if (personalRemaining != null) {
      if (trackedStock != null) {
        return Math.max(0, Math.min(trackedStock, personalRemaining));
      }
      return Math.max(0, personalRemaining);
    }
    if (trackedStock != null) {
      return Math.max(0, trackedStock);
    }
    int fallback = 64;
    Integer configured = product.itemAmount();
    if (configured == null || configured <= 0) {
      return fallback;
    }
    return Math.max(1, configured);
  }

  private void validatePurchaseQuantity(int quantity, int maxQuantity) {
    if (maxQuantity <= 0) {
      throw new ServiceException("out_of_stock", "Product is sold out.");
    }
    if (quantity < 1 || quantity > maxQuantity) {
      throw new ServiceException("invalid_quantity", "Quantity must be between 1 and " + maxQuantity);
    }
  }

  private void reserveProductStock(Connection connection, long productId, int quantity) throws SQLException {
    if (quantity <= 0) {
      return;
    }
    String sql = """
        UPDATE products
        SET stock_remaining = stock_remaining - ?
        WHERE id = ?
          AND stock_remaining IS NOT NULL
          AND stock_remaining >= ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, quantity);
      statement.setLong(2, productId);
      statement.setInt(3, quantity);
      int updated = statement.executeUpdate();
      if (updated > 0) {
        return;
      }
    }

    String checkSql =
        "SELECT stock_remaining FROM products WHERE id = ?" + sqlProvider.forUpdateClause();
    try (PreparedStatement statement = connection.prepareStatement(checkSql)) {
      statement.setLong(1, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("product_missing", "Product not found");
        }
        Integer stockRemaining = (Integer) resultSet.getObject("stock_remaining");
        if (stockRemaining == null) {
          return;
        }
      }
    }
    throw new ServiceException("out_of_stock", "Not enough stock remains for this product.");
  }

  private void restoreProductStock(Connection connection, long productId, int quantity) throws SQLException {
    if (productId <= 0 || quantity <= 0) {
      return;
    }
    String sql = """
        UPDATE products
        SET stock_remaining = CASE
            WHEN item_amount IS NULL THEN stock_remaining
            ELSE LEAST(item_amount, stock_remaining + ?)
          END
        WHERE id = ?
          AND stock_remaining IS NOT NULL
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, quantity);
      statement.setLong(2, productId);
      statement.executeUpdate();
    }
  }

  private void consumePersonalLimitQuota(
      Connection connection,
      long userId,
      ProductService.ProductView product,
      int quantity) throws SQLException {
    Integer perUserLimit = product.perUserLimit();
    if (perUserLimit == null || quantity <= 0) {
      return;
    }
    int usedCount = readPersonalLimitUsageForUpdate(connection, product.id(), userId);
    int remaining = Math.max(0, perUserLimit - usedCount);
    if (quantity > remaining) {
      throw new ServiceException("product_user_limit_reached", "You have reached the purchase limit for this product.");
    }
    incrementPersonalLimitUsage(connection, product.id(), userId, quantity);
  }

  private int readPersonalLimitUsageForUpdate(Connection connection, long productId, long userId)
      throws SQLException {
    String sql =
        """
        SELECT used_count
        FROM product_user_usage
        WHERE product_id = ? AND user_id = ?
        %s
        """
            .formatted(sqlProvider.forUpdateClause());
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, productId);
      statement.setLong(2, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return 0;
        }
        return Math.max(0, resultSet.getInt("used_count"));
      }
    }
  }

  private void incrementPersonalLimitUsage(Connection connection, long productId, long userId, int quantity)
      throws SQLException {
    String sql = sqlProvider.upsertProductUserUsageSql();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, productId);
      statement.setLong(2, userId);
      statement.setInt(3, quantity);
      statement.executeUpdate();
    }
  }

  private void reducePersonalLimitUsage(Connection connection, long productId, long userId, int quantity)
      throws SQLException {
    if (productId <= 0 || userId <= 0 || quantity <= 0) {
      return;
    }
    String updateSql = """
        UPDATE product_user_usage
        SET used_count = GREATEST(0, used_count - ?),
            updated_at = CURRENT_TIMESTAMP
        WHERE product_id = ? AND user_id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
      statement.setInt(1, quantity);
      statement.setLong(2, productId);
      statement.setLong(3, userId);
      statement.executeUpdate();
    }
    String deleteSql = "DELETE FROM product_user_usage WHERE product_id = ? AND user_id = ? AND used_count <= 0";
    try (PreparedStatement statement = connection.prepareStatement(deleteSql)) {
      statement.setLong(1, productId);
      statement.setLong(2, userId);
      statement.executeUpdate();
    }
  }

  private DeliveryMode resolveDeliveryMode(String rawMode, ProductService.ProductType productType) {
    DeliveryMode defaultMode = defaultDeliveryMode(productType);
    if (rawMode == null || rawMode.isBlank()) {
      return defaultMode;
    }
    String normalized = rawMode.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "IMMEDIATE" -> DeliveryMode.IMMEDIATE;
      case "CLAIM", "MANUAL", "MANUAL_CLAIM" -> DeliveryMode.CLAIM;
      default -> throw new ServiceException("invalid_delivery_mode", "Delivery mode is invalid");
    };
  }

  private DeliveryMode defaultDeliveryMode(ProductService.ProductType productType) {
    return switch (productType) {
      case COMMAND, POTION_EFFECT -> DeliveryMode.CLAIM;
      case GIVE_ITEM, RECYCLE_ITEM, GROUP_BUY_VOUCHER -> DeliveryMode.IMMEDIATE;
    };
  }

  private ExistingOrder readExistingOrder(Connection connection, long userId, String idempotencyKey)
      throws SQLException {
    String sql =
        """
        SELECT o.order_no, o.currency, o.total_amount, o.status, o.refund_deadline,
               gv.code AS group_buy_voucher_code, gv.status AS group_buy_voucher_status,
               gv.consumed_at AS group_buy_voucher_consumed_at
        FROM orders o
        LEFT JOIN group_buy_vouchers gv ON gv.order_id = o.id
        WHERE o.user_id = ? AND o.idempotency_key = ?
        %s
        """
            .formatted(sqlProvider.forUpdateClause());
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      statement.setString(2, idempotencyKey);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        Timestamp refundDeadline = resultSet.getTimestamp("refund_deadline");
        Timestamp consumedAt = resultSet.getTimestamp("group_buy_voucher_consumed_at");
        return new ExistingOrder(
            resultSet.getString("order_no"),
            resultSet.getString("currency"),
            resultSet.getLong("total_amount"),
            resultSet.getString("status"),
            refundDeadline == null ? null : refundDeadline.toLocalDateTime(),
            resultSet.getString("group_buy_voucher_code"),
            resultSet.getString("group_buy_voucher_status"),
            consumedAt == null ? null : consumedAt.toLocalDateTime());
      }
    }
  }

  private UUID readBoundUuidForUpdate(Connection connection, long userId) throws SQLException {
    String sql =
        "SELECT bound_uuid FROM web_users WHERE id = ?" + sqlProvider.forUpdateClause();
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

  private void lockUserForUpdate(Connection connection, long userId) throws SQLException {
    String sql =
        "SELECT id FROM web_users WHERE id = ?" + sqlProvider.forUpdateClause();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("user_missing", "User not found");
        }
      }
    }
  }

  private void lockProductForUpdate(Connection connection, long productId) throws SQLException {
    String sql =
        "SELECT id FROM products WHERE id = ?" + sqlProvider.forUpdateClause();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("product_missing", "Product not found");
        }
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
      String status,
      String idempotencyKey,
      LocalDateTime refundDeadline,
      String targetServerId) throws SQLException {
    String sql = """
        INSERT INTO orders (
          order_no,
          user_id,
          mc_uuid,
          currency,
          total_amount,
          status,
          idempotency_key,
          target_server_id,
          refund_deadline
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement statement =
             connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, orderNo);
      statement.setLong(2, userId);
      statement.setString(3, playerUuid.toString());
      statement.setString(4, currency.name());
      statement.setLong(5, totalAmount);
      statement.setString(6, status);
      statement.setString(7, idempotencyKey);
      statement.setString(8, targetServerId);
      if (refundDeadline == null) {
        statement.setTimestamp(9, null);
      } else {
        statement.setTimestamp(9, Timestamp.valueOf(refundDeadline));
      }
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
             connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
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

  private String insertGroupBuyVoucher(
      Connection connection,
      long orderId,
      long userId,
      long productId) throws SQLException {
    for (int attempt = 0; attempt < 8; attempt++) {
      String code = randomGroupBuyVoucherCode();
      String sql = """
          INSERT INTO group_buy_vouchers (
            code, order_id, user_id, product_id, status
          )
          VALUES (?, ?, ?, ?, 'ISSUED')
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, code);
        statement.setLong(2, orderId);
        statement.setLong(3, userId);
        statement.setLong(4, productId);
        statement.executeUpdate();
        return code;
      } catch (SQLException exception) {
        if (isDuplicateKey(exception)) {
          continue;
        }
        throw exception;
      }
    }
    throw new IllegalStateException("Failed to generate unique group-buy voucher code");
  }

  private void insertDelivery(
      Connection connection,
      long orderId,
      long itemId,
      UUID playerUuid,
      String targetServerId,
      DeliveryTaskSpec taskSpec,
      DeliveryMode deliveryMode,
      LocalDateTime nextRetryAt) throws SQLException {
    String status = deliveryMode == DeliveryMode.CLAIM ? "WAIT_CLAIM" : "PENDING";
    String sql = """
        INSERT INTO delivery_queue (
          order_id, item_id, mc_uuid, target_server_id, command_text, delivery_kind, payload_json,
          manual_claim, quantity, status, next_retry_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, orderId);
      statement.setLong(2, itemId);
      statement.setString(3, playerUuid.toString());
      statement.setString(4, targetServerId);
      statement.setString(5, taskSpec.commandText());
      statement.setString(6, taskSpec.kind().name());
      statement.setString(7, taskSpec.payloadJson());
      statement.setBoolean(8, deliveryMode == DeliveryMode.CLAIM);
      statement.setInt(9, taskSpec.quantity());
      statement.setString(10, status);
      statement.setTimestamp(11, Timestamp.valueOf(nextRetryAt));
      statement.executeUpdate();
    }
    if (deliveryMode == DeliveryMode.CLAIM) {
      ClaimTokenRepository.ensureOrderToken(connection, orderId, sqlProvider.forUpdateClause());
    }
  }

  private String resolveTargetServerId(Connection connection, UUID playerUuid) throws SQLException {
    if (playerUuid == null) {
      return null;
    }
    String onlineServer = playerPresenceService.resolveOnlineServer(connection, playerUuid);
    if (onlineServer != null && !onlineServer.isBlank()) {
      return onlineServer;
    }
    if (isPlayerOnline(playerUuid)) {
      String localServerId = settingsSupplier.get().clusterSettings().serverId();
      return localServerId == null || localServerId.isBlank() ? null : localServerId;
    }
    return null;
  }

  private boolean isPlayerOnline(UUID playerUuid) {
    if (playerUuid == null) {
      return false;
    }
    try {
      return schedulerBridge
          .supplyPlayer(playerUuid, Player::isOnline)
          .completeOnTimeout(false, 1L, TimeUnit.SECONDS)
          .exceptionally(ignored -> false)
          .join();
    } catch (Exception ignored) {
      return false;
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

  private int normalizedOrderCooldownSeconds() {
    int value = settingsSupplier.get().orderCooldownSeconds();
    if (value < 0) {
      return 0;
    }
    return value;
  }

  private String newOrderNo() {
    long timestamp = System.currentTimeMillis();
    int randomPart = secureRandom.nextInt(1_000_000);
    return String.format(Locale.ROOT, "ODR-%d-%06d", timestamp, randomPart);
  }

  private String randomGroupBuyVoucherCode() {
    StringBuilder builder = new StringBuilder(15);
    builder.append("GB-");
    for (int index = 0; index < 12; index++) {
      int pointer = secureRandom.nextInt(GROUP_BUY_VOUCHER_ALPHABET.length());
      builder.append(GROUP_BUY_VOUCHER_ALPHABET.charAt(pointer));
    }
    return builder.toString();
  }

  private boolean isDuplicateKey(SQLException exception) {
    if (exception == null) {
      return false;
    }
    String state = exception.getSQLState();
    int errorCode = exception.getErrorCode();
    String message = exception.getMessage();
    if ("23000".equals(state) || errorCode == 1062) {
      return true;
    }
    return message != null && message.toLowerCase(Locale.ROOT).contains("duplicate");
  }

  private boolean hasEnoughItem(Player player, Material material, int requiredAmount) {
    int count = 0;
    ItemStack[] contents = player.getInventory().getContents();
    for (ItemStack itemStack : contents) {
      if (itemStack == null || itemStack.getType() != material) {
        continue;
      }
      count += itemStack.getAmount();
      if (count >= requiredAmount) {
        return true;
      }
    }
    return false;
  }

  private boolean removeItems(Player player, Material material, int requiredAmount) {
    int remaining = requiredAmount;
    ItemStack[] contents = player.getInventory().getContents();
    for (int index = 0; index < contents.length; index++) {
      ItemStack stack = contents[index];
      if (stack == null || stack.getType() != material) {
        continue;
      }
      int stackAmount = stack.getAmount();
      if (stackAmount <= remaining) {
        contents[index] = null;
        remaining -= stackAmount;
      } else {
        stack.setAmount(stackAmount - remaining);
        remaining = 0;
      }
      if (remaining <= 0) {
        break;
      }
    }
    player.getInventory().setContents(contents);
    player.updateInventory();
    return remaining <= 0;
  }

  private void restoreItems(Player player, Material material, int amount) {
    if (amount <= 0) {
      return;
    }
    ItemStack stack = new ItemStack(material, amount);
    player.getInventory().addItem(stack)
        .values()
        .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
  }

  private OrderPlacementResult runOnPlayer(
      long userId,
      java.util.concurrent.Callable<OrderPlacementResult> task) {
    UUID playerUuid = databaseManager.withConnection(connection -> readBoundUuidForUpdate(connection, userId));
    try {
      return schedulerBridge
          .supplyPlayer(playerUuid, ignoredPlayer -> callOrderTask(task))
          .orTimeout(8L, TimeUnit.SECONDS)
          .join();
    } catch (CompletionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new ServiceException("internal_error", "Sync task failed");
    }
  }

  private OrderPlacementResult callOrderTask(
      java.util.concurrent.Callable<OrderPlacementResult> task) {
    try {
      return task.call();
    } catch (Exception exception) {
      if (exception instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new ServiceException("internal_error", "Sync task failed");
    }
  }

  List<OrderView> listOrdersForUser(long userId, int limit, Long cursor) {
    int pageSize = Math.min(Math.max(1, limit), 200);
    return databaseManager.withConnection(connection -> {
      LocalDateTime cutoff = cursor == null ? null : readOrderCreatedAtById(connection, cursor);
      List<OrderView> official = listOfficialOrders(connection, userId, pageSize, cursor);
      List<OrderView> market = listMarketOrders(connection, userId, pageSize, cutoff);
      return mergeOrders(official, market, pageSize);
    });
  }

  private LocalDateTime readOrderCreatedAtById(Connection connection, long orderId) throws SQLException {
    String sql = "SELECT created_at FROM orders WHERE id = ? LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, orderId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return resultSet.getTimestamp("created_at").toLocalDateTime();
      }
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Cursor clause is selected from a fixed branch and all external values are bound")
  private List<OrderView> listOfficialOrders(
      Connection connection,
      long userId,
      int pageSize,
      Long cursor) throws SQLException {
    String cursorSql = cursor == null ? "" : " AND o.id < ?";
    String sql = """
        SELECT o.id, o.order_no, o.user_id, o.mc_uuid, o.currency, o.total_amount, o.status,
               o.claim_token,
               o.created_at, o.delivered_at, o.refund_deadline, o.refunded_at,
               oi.quantity, oi.unit_price,
               p.sku, p.title, p.remark, p.product_type, p.item_material, p.item_amount,
               p.effect_type, p.effect_seconds, p.effect_amplifier,
               gv.code AS group_buy_voucher_code,
               gv.status AS group_buy_voucher_status,
               gv.consumed_at AS group_buy_voucher_consumed_at
        FROM orders o
        JOIN order_items oi ON oi.order_id = o.id
        JOIN products p ON p.id = oi.product_id
        LEFT JOIN group_buy_vouchers gv ON gv.order_id = o.id
        WHERE o.user_id = ?
        """ + cursorSql + """
        ORDER BY o.id DESC
        LIMIT ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int index = 1;
      statement.setLong(index++, userId);
      if (cursor != null) {
        statement.setLong(index++, cursor);
      }
      statement.setInt(index, pageSize);

      List<OrderView> results = new ArrayList<>();
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          String status = resultSet.getString("status");
          String claimToken = resultSet.getString("claim_token");
          long orderId = resultSet.getLong("id");
          String ensuredToken = ensureOrderClaimToken(connection, orderId, status, claimToken);
          results.add(readOrderView(resultSet, ensuredToken));
        }
      }
      return results;
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Cutoff clause is selected from a fixed branch and all external values are bound")
  private List<OrderView> listMarketOrders(
      Connection connection,
      long userId,
      int pageSize,
      LocalDateTime cutoff) throws SQLException {
    String cutoffSql = cutoff == null ? "" : " AND mt.created_at < ?";
    String sql = """
        SELECT mt.id AS trade_id, mt.listing_id, mt.currency, mt.unit_price, mt.quantity AS trade_quantity,
               mt.total_price, mt.buyer_total,
               mt.status AS trade_status, mt.claim_token, mt.refund_deadline, mt.refunded_at, mt.created_at,
               ml.item_material, ml.remark, ml.buyer_uuid,
               md.status AS delivery_status, md.delivered_at
        FROM market_trades mt
        JOIN market_listings ml ON ml.id = mt.listing_id
        LEFT JOIN market_item_deliveries md
          ON md.trade_id = mt.id AND md.delivery_type = 'SALE'
        WHERE mt.buyer_user_id = ?
        """ + cutoffSql + """
        ORDER BY mt.created_at DESC
        LIMIT ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int index = 1;
      statement.setLong(index++, userId);
      if (cutoff != null) {
        statement.setTimestamp(index++, Timestamp.valueOf(cutoff));
      }
      statement.setInt(index, pageSize);

      List<OrderView> results = new ArrayList<>();
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          String tradeStatus = resultSet.getString("trade_status");
          String claimToken = resultSet.getString("claim_token");
          long tradeId = resultSet.getLong("trade_id");
          String ensuredToken = ensureMarketClaimToken(connection, tradeId, tradeStatus, claimToken);
          results.add(readMarketOrderView(resultSet, userId, ensuredToken));
        }
      }
      return results;
    }
  }

  private List<OrderView> mergeOrders(
      List<OrderView> official,
      List<OrderView> market,
      int limit) {
    List<OrderView> merged = new ArrayList<>();
    int i = 0;
    int j = 0;
    while (merged.size() < limit && (i < official.size() || j < market.size())) {
      if (j >= market.size()) {
        merged.add(official.get(i++));
        continue;
      }
      if (i >= official.size()) {
        merged.add(market.get(j++));
        continue;
      }
      OrderView a = official.get(i);
      OrderView b = market.get(j);
      if (a.createdAt().isAfter(b.createdAt())) {
        merged.add(a);
        i++;
      } else {
        merged.add(b);
        j++;
      }
    }
    return merged;
  }

  List<AdminOrderView> listOrdersForAdmin(
      int limit,
      Long cursor,
      String status,
      Long userId,
      String orderNo,
      String usernameKeyword,
      String keyword,
      String currencyFilter,
      String productTypeFilter) {
    int pageSize = Math.min(Math.max(1, limit), 300);
    String normalizedStatus = status == null ? null : status.trim().toUpperCase(Locale.ROOT);
    String normalizedOrderNo = orderNo == null ? null : orderNo.trim();
    String normalizedUsername = usernameKeyword == null ? null : usernameKeyword.trim().toLowerCase(Locale.ROOT);
    String normalizedKeyword = keyword == null ? null : keyword.trim().toLowerCase(Locale.ROOT);
    String normalizedCurrency = currencyFilter == null ? null : currencyFilter.trim().toUpperCase(Locale.ROOT);
    String normalizedProductType = productTypeFilter == null
        ? null
        : productTypeFilter.trim().toUpperCase(Locale.ROOT);
    return databaseManager.withConnection(connection -> listOrdersForAdmin(
        connection,
        pageSize,
        cursor,
        userId,
        normalizedStatus,
        normalizedOrderNo,
        normalizedUsername,
        normalizedKeyword,
        normalizedCurrency,
        normalizedProductType));
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Admin order filters append constant SQL fragments and bind every user-provided value")
  private List<AdminOrderView> listOrdersForAdmin(
      Connection connection,
      int pageSize,
      Long cursor,
      Long userId,
      String normalizedStatus,
      String normalizedOrderNo,
      String normalizedUsername,
      String normalizedKeyword,
      String normalizedCurrency,
      String normalizedProductType) throws SQLException {
      List<String> clauses = new ArrayList<>();
      clauses.add("1=1");
      if (normalizedStatus != null && !normalizedStatus.isBlank()) {
        clauses.add("o.status = ?");
      }
      if (userId != null && userId > 0) {
        clauses.add("o.user_id = ?");
      }
      if (normalizedOrderNo != null && !normalizedOrderNo.isBlank()) {
        clauses.add("o.order_no = ?");
      }
      if (normalizedUsername != null && !normalizedUsername.isBlank()) {
        clauses.add("LOWER(u.username) LIKE ?");
      }
      if (normalizedKeyword != null && !normalizedKeyword.isBlank()) {
        clauses.add("(LOWER(o.order_no) LIKE ? OR LOWER(p.sku) LIKE ? OR LOWER(p.title) LIKE ?)");
      }
      if (normalizedCurrency != null && !normalizedCurrency.isBlank()) {
        clauses.add("o.currency = ?");
      }
      if (normalizedProductType != null && !normalizedProductType.isBlank()) {
        clauses.add("p.product_type = ?");
      }
      if (cursor != null) {
        clauses.add("o.id < ?");
      }

      String sql = """
          SELECT o.id, o.order_no, o.user_id, o.mc_uuid, o.currency, o.total_amount, o.status,
                 o.claim_token,
                 o.created_at, o.delivered_at, o.refund_deadline, o.refunded_at,
                 u.username, u.bound_uuid,
                 oi.quantity, oi.unit_price,
                 p.sku, p.title, p.remark, p.product_type, p.item_material, p.item_amount,
                 p.effect_type, p.effect_seconds, p.effect_amplifier,
                 gv.code AS group_buy_voucher_code,
                 gv.status AS group_buy_voucher_status,
                 gv.consumed_at AS group_buy_voucher_consumed_at
          FROM orders o
          JOIN web_users u ON u.id = o.user_id
          JOIN order_items oi ON oi.order_id = o.id
          JOIN products p ON p.id = oi.product_id
          LEFT JOIN group_buy_vouchers gv ON gv.order_id = o.id
          """
          + " WHERE " + String.join(" AND ", clauses)
          + " ORDER BY o.id DESC"
          + " LIMIT ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        int index = 1;
        if (normalizedStatus != null && !normalizedStatus.isBlank()) {
          statement.setString(index++, normalizedStatus);
        }
        if (userId != null && userId > 0) {
          statement.setLong(index++, userId);
        }
        if (normalizedOrderNo != null && !normalizedOrderNo.isBlank()) {
          statement.setString(index++, normalizedOrderNo);
        }
        if (normalizedUsername != null && !normalizedUsername.isBlank()) {
          statement.setString(index++, "%" + normalizedUsername + "%");
        }
        if (normalizedKeyword != null && !normalizedKeyword.isBlank()) {
          String fuzzy = "%" + normalizedKeyword + "%";
          statement.setString(index++, fuzzy);
          statement.setString(index++, fuzzy);
          statement.setString(index++, fuzzy);
        }
        if (normalizedCurrency != null && !normalizedCurrency.isBlank()) {
          statement.setString(index++, normalizedCurrency);
        }
        if (normalizedProductType != null && !normalizedProductType.isBlank()) {
          statement.setString(index++, normalizedProductType);
        }
        if (cursor != null) {
          statement.setLong(index++, cursor);
        }
        statement.setInt(index, pageSize);

        List<AdminOrderView> results = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            String statusValue = resultSet.getString("status");
            String claimToken = resultSet.getString("claim_token");
            long orderId = resultSet.getLong("id");
            OrderView view = readOrderView(
                resultSet,
                ensureOrderClaimToken(connection, orderId, statusValue, claimToken));
            String boundUuidRaw = resultSet.getString("bound_uuid");
            UUID boundUuid = boundUuidRaw == null ? null : UUID.fromString(boundUuidRaw);
            results.add(new AdminOrderView(
                view,
                resultSet.getString("username"),
                boundUuid));
          }
        }
        return results;
      }
  }

  GroupBuyVoucherConsumeResult consumeGroupBuyVoucher(long adminUserId, String rawCode) {
    String code = normalizeGroupBuyVoucherCode(rawCode);
    return databaseManager.inTransaction(connection -> {
      GroupBuyVoucherRow row = readGroupBuyVoucherForUpdate(connection, code);
      if (row == null) {
        throw new ServiceException("voucher_missing", "Group-buy voucher not found");
      }
      if (!"ISSUED".equalsIgnoreCase(row.status())) {
        if ("REFUNDED".equalsIgnoreCase(row.status())) {
          throw new ServiceException("voucher_refunded", "Group-buy voucher has been refunded");
        }
        throw new ServiceException("voucher_unavailable", "Group-buy voucher is already consumed");
      }

      String updateSql = """
          UPDATE group_buy_vouchers
          SET status = 'CONSUMED',
              consumed_by_admin_id = ?,
              consumed_at = CURRENT_TIMESTAMP
          WHERE id = ?
            AND status = 'ISSUED'
          """;
      try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
        statement.setLong(1, adminUserId);
        statement.setLong(2, row.id());
        int updated = statement.executeUpdate();
        if (updated == 0) {
          throw new ServiceException("voucher_unavailable", "Group-buy voucher is already consumed");
        }
      }

      GroupBuyVoucherRow refreshed = readGroupBuyVoucherForUpdate(connection, code);
      if (refreshed == null) {
        throw new ServiceException("voucher_missing", "Group-buy voucher not found");
      }
      return new GroupBuyVoucherConsumeResult(
          refreshed.code(),
          refreshed.status(),
          refreshed.orderNo(),
          refreshed.userId(),
          refreshed.username(),
          refreshed.productSku(),
          refreshed.productTitle(),
          refreshed.consumedAt());
    });
  }

  RefundResult refundOrder(long userId, String orderNo) {
    if (orderNo == null || orderNo.isBlank()) {
      throw new ServiceException("order_missing", "Order number is required");
    }
    String normalizedOrderNo = orderNo.trim();
    if (normalizedOrderNo.regionMatches(true, 0, "MKT-", 0, 4)) {
      return refundMarketOrder(userId, normalizedOrderNo);
    }
    OrderRow order = databaseManager.inTransaction(connection -> {
      OrderRow row = readOrderForRefund(connection, userId, normalizedOrderNo);
      if (row == null) {
        throw new ServiceException("order_missing", "Order not found");
      }
      lockProductForUpdate(connection, row.productId());
      lockUserForUpdate(connection, userId);
      if ("REFUNDED".equalsIgnoreCase(row.status())) {
        throw new ServiceException("already_refunded", "Order has already been refunded");
      }
      validateOfficialRefund(row);

      walletService.applyDelta(
          connection,
          userId,
          CurrencyType.valueOf(row.currency()),
          row.totalAmount(),
          "ORDER_REFUND",
          row.orderNo() + ":refund",
          false);

      String updateOrderSql = """
          UPDATE orders
          SET status = 'REFUNDED', refunded_at = CURRENT_TIMESTAMP, claim_token = NULL
          WHERE id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(updateOrderSql)) {
        statement.setLong(1, row.id());
        statement.executeUpdate();
      }
      restoreProductStock(connection, row.productId(), row.quantity());
      reducePersonalLimitUsage(connection, row.productId(), userId, row.quantity());

      if (row.groupBuyVoucherCode() != null) {
        String updateVoucherSql = """
            UPDATE group_buy_vouchers
            SET status = 'REFUNDED'
            WHERE code = ?
              AND status = 'ISSUED'
            """;
        try (PreparedStatement statement = connection.prepareStatement(updateVoucherSql)) {
          statement.setString(1, row.groupBuyVoucherCode());
          statement.executeUpdate();
        }
      }

      String cancelDeliverySql = """
          UPDATE delivery_queue
          SET status = 'CANCELLED', last_error = 'Refunded'
          WHERE order_id = ?
            AND status IN ('PENDING', 'WAIT_CLAIM')
          """;
      try (PreparedStatement statement = connection.prepareStatement(cancelDeliverySql)) {
        statement.setLong(1, row.id());
        statement.executeUpdate();
      }

      return row;
    });

    WalletService.WalletBalance balance = walletService.getBalance(userId);
    return new RefundResult(order.orderNo(), balance);
  }

  int resetProductUserLimitUsage(long productId) {
    if (productId <= 0) {
      throw new ServiceException("invalid_product", "Product id must be positive");
    }
    return databaseManager.inTransaction(connection -> {
      String sql = "DELETE FROM product_user_usage WHERE product_id = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, productId);
        return statement.executeUpdate();
      }
    });
  }

  private GroupBuyVoucherRow readGroupBuyVoucherForUpdate(Connection connection, String code)
      throws SQLException {
    String sql =
        """
        SELECT gv.id, gv.code, gv.status, gv.consumed_at,
               o.order_no, o.user_id, u.username, p.sku, p.title
        FROM group_buy_vouchers gv
        JOIN orders o ON o.id = gv.order_id
        JOIN web_users u ON u.id = gv.user_id
        JOIN products p ON p.id = gv.product_id
        WHERE gv.code = ?
        %s
        """
            .formatted(sqlProvider.forUpdateClause());
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, code);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        Timestamp consumedAt = resultSet.getTimestamp("consumed_at");
        return new GroupBuyVoucherRow(
            resultSet.getLong("id"),
            resultSet.getString("code"),
            resultSet.getString("status"),
            resultSet.getString("order_no"),
            resultSet.getLong("user_id"),
            resultSet.getString("username"),
            resultSet.getString("sku"),
            resultSet.getString("title"),
            consumedAt == null ? null : consumedAt.toLocalDateTime());
      }
    }
  }

  private void validateOfficialRefund(OrderRow row) {
    if (row.groupBuyVoucherCode() != null) {
      String voucherStatus = String.valueOf(row.groupBuyVoucherStatus()).toUpperCase(Locale.ROOT);
      if ("CONSUMED".equals(voucherStatus)) {
        throw new ServiceException("voucher_consumed", "Group-buy voucher has already been consumed");
      }
      if ("REFUNDED".equals(voucherStatus)) {
        throw new ServiceException("already_refunded", "Order has already been refunded");
      }
      if (settingsSupplier.get().refundUndeliveredEnabled() && "ISSUED".equals(voucherStatus)) {
        return;
      }
    }

    if (settingsSupplier.get().refundUndeliveredEnabled()) {
      if ("PENDING".equalsIgnoreCase(row.status()) || "WAIT_CLAIM".equalsIgnoreCase(row.status())) {
        return;
      }
      throw new ServiceException("refund_not_allowed", "Order is not refundable");
    }

    if (!"PENDING".equalsIgnoreCase(row.status())) {
      throw new ServiceException("refund_not_allowed", "Order is not refundable");
    }
    if (row.refundDeadline() == null) {
      throw new ServiceException("refund_disabled", "Refund is disabled for this order");
    }
    if (LocalDateTime.now().isAfter(row.refundDeadline())) {
      throw new ServiceException("refund_expired", "Refund window has expired");
    }
  }

  private void validateMarketRefund(MarketOrderRow row) {
    if (settingsSupplier.get().refundUndeliveredEnabled()) {
      if ("PENDING".equalsIgnoreCase(row.status()) || "WAIT_CLAIM".equalsIgnoreCase(row.status())) {
        return;
      }
      throw new ServiceException("refund_not_allowed", "Order is not refundable");
    }

    if (!"PENDING".equalsIgnoreCase(row.status())) {
      throw new ServiceException("refund_not_allowed", "Order is not refundable");
    }
    if (row.refundDeadline() == null) {
      throw new ServiceException("refund_disabled", "Refund is disabled for this order");
    }
    if (LocalDateTime.now().isAfter(row.refundDeadline())) {
      throw new ServiceException("refund_expired", "Refund window has expired");
    }
  }

  private String normalizeGroupBuyVoucherCode(String rawCode) {
    if (rawCode == null || rawCode.isBlank()) {
      throw new ServiceException("invalid_voucher", "Group-buy voucher code is required");
    }
    String code = rawCode.trim().toUpperCase(Locale.ROOT);
    if (!code.matches("^GB-[A-Z0-9]{8,32}$")) {
      throw new ServiceException("invalid_voucher", "Group-buy voucher code format is invalid");
    }
    return code;
  }

  private RefundResult refundMarketOrder(long userId, String orderNo) {
    long tradeId = parseMarketTradeId(orderNo);
    databaseManager.inTransaction(connection -> {
      MarketOrderRow row = readMarketTradeForRefund(connection, userId, tradeId);
      if (row == null) {
        throw new ServiceException("order_missing", "Order not found");
      }
      if ("REFUNDED".equalsIgnoreCase(row.status())) {
        throw new ServiceException("already_refunded", "Order has already been refunded");
      }
      validateMarketRefund(row);

      long refundAmount = row.buyerTotal() > 0 ? row.buyerTotal() : row.totalPrice();
      walletService.applyDelta(
          connection,
          userId,
          CurrencyType.valueOf(row.currency()),
          refundAmount,
          "ORDER_REFUND",
          orderNo + ":refund",
          false);

      String updateTradeSql = """
          UPDATE market_trades
          SET status = 'REFUNDED', refunded_at = CURRENT_TIMESTAMP, claim_token = NULL
          WHERE id = ? AND status IN ('PENDING', 'WAIT_CLAIM')
          """;
      try (PreparedStatement statement = connection.prepareStatement(updateTradeSql)) {
        statement.setLong(1, row.tradeId());
        statement.executeUpdate();
      }

      String cancelDeliverySql = """
          UPDATE market_item_deliveries
          SET status = 'CANCELLED', last_error = 'Refunded'
          WHERE trade_id = ?
            AND delivery_type = 'SALE'
            AND status IN ('PENDING', 'WAIT_CLAIM')
          """;
      try (PreparedStatement statement = connection.prepareStatement(cancelDeliverySql)) {
        statement.setLong(1, row.tradeId());
        statement.executeUpdate();
      }

      String restoreListingSql = """
          UPDATE market_listings
          SET quantity = quantity + ?,
              quantity_total = GREATEST(quantity_total, quantity + ?),
              status = 'ACTIVE',
              buyer_user_id = NULL,
              buyer_uuid = NULL,
              sold_at = NULL
          WHERE id = ?
            AND status <> 'UNLISTED'
          """;
      try (PreparedStatement statement = connection.prepareStatement(restoreListingSql)) {
        statement.setInt(1, row.quantity());
        statement.setInt(2, row.quantity());
        statement.setLong(3, row.listingId());
        statement.executeUpdate();
      }

      return row;
    });

    WalletService.WalletBalance balance = walletService.getBalance(userId);
    return new RefundResult(orderNo, balance);
  }

  private long parseMarketTradeId(String orderNo) {
    if (orderNo == null) {
      throw new ServiceException("order_missing", "Order number is required");
    }
    String normalized = orderNo.trim();
    if (!normalized.regionMatches(true, 0, "MKT-", 0, 4)) {
      throw new ServiceException("order_missing", "Order not found");
    }
    String rawId = normalized.substring(4);
    try {
      long tradeId = Long.parseLong(rawId);
      if (tradeId <= 0L) {
        throw new NumberFormatException("trade id must be positive");
      }
      return tradeId;
    } catch (NumberFormatException exception) {
      throw new ServiceException("order_missing", "Order not found");
    }
  }

  private OrderRow readOrderForRefund(Connection connection, long userId, String orderNo)
      throws SQLException {
    String sql =
        """
        SELECT o.id, o.order_no, o.currency, o.total_amount, o.status, o.refund_deadline,
               oi.product_id, oi.quantity,
               gv.code AS group_buy_voucher_code, gv.status AS group_buy_voucher_status
        FROM orders o
        JOIN order_items oi ON oi.order_id = o.id
        LEFT JOIN group_buy_vouchers gv ON gv.order_id = o.id
        WHERE o.user_id = ? AND o.order_no = ?
        %s
        """
            .formatted(sqlProvider.forUpdateClause());
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      statement.setString(2, orderNo);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        Timestamp refundDeadline = resultSet.getTimestamp("refund_deadline");
        return new OrderRow(
            resultSet.getLong("id"),
            resultSet.getString("order_no"),
            resultSet.getString("currency"),
            resultSet.getLong("total_amount"),
            resultSet.getString("status"),
            refundDeadline == null ? null : refundDeadline.toLocalDateTime(),
            resultSet.getLong("product_id"),
            resultSet.getInt("quantity"),
            resultSet.getString("group_buy_voucher_code"),
            resultSet.getString("group_buy_voucher_status"));
      }
    }
  }

  private MarketOrderRow readMarketTradeForRefund(Connection connection, long userId, long tradeId)
      throws SQLException {
    String sql =
        """
        SELECT id, listing_id, currency, unit_price, quantity, total_price, buyer_total, status, refund_deadline
        FROM market_trades
        WHERE id = ? AND buyer_user_id = ?
        %s
        """
            .formatted(sqlProvider.forUpdateClause());
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, tradeId);
      statement.setLong(2, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        Timestamp refundDeadline = resultSet.getTimestamp("refund_deadline");
        return new MarketOrderRow(
            resultSet.getLong("id"),
            resultSet.getLong("listing_id"),
            resultSet.getString("currency"),
            resultSet.getLong("unit_price"),
            resultSet.getInt("quantity"),
            resultSet.getLong("total_price"),
            resultSet.getLong("buyer_total"),
            resultSet.getString("status"),
            refundDeadline == null ? null : refundDeadline.toLocalDateTime());
      }
    }
  }

  private OrderView readOrderView(ResultSet resultSet, String claimTokenOverride) throws SQLException {
    Timestamp deliveredAt = resultSet.getTimestamp("delivered_at");
    Timestamp refundDeadline = resultSet.getTimestamp("refund_deadline");
    Timestamp refundedAt = resultSet.getTimestamp("refunded_at");
    Timestamp groupBuyVoucherConsumedAt = resultSet.getTimestamp("group_buy_voucher_consumed_at");
    String claimToken = claimTokenOverride != null ? claimTokenOverride : resultSet.getString("claim_token");
    return new OrderView(
        resultSet.getLong("id"),
        resultSet.getString("order_no"),
        resultSet.getLong("user_id"),
        UUID.fromString(resultSet.getString("mc_uuid")),
        CurrencyType.valueOf(resultSet.getString("currency")),
        resultSet.getLong("total_amount"),
        resultSet.getString("status"),
        resultSet.getTimestamp("created_at").toLocalDateTime(),
        deliveredAt == null ? null : deliveredAt.toLocalDateTime(),
        refundedAt == null ? null : refundedAt.toLocalDateTime(),
        refundDeadline == null ? null : refundDeadline.toLocalDateTime(),
        resultSet.getString("sku"),
        resultSet.getString("title"),
        resultSet.getString("remark"),
        resultSet.getString("product_type"),
        resultSet.getString("item_material"),
        (Integer) resultSet.getObject("item_amount"),
        resultSet.getString("effect_type"),
        (Integer) resultSet.getObject("effect_seconds"),
        (Integer) resultSet.getObject("effect_amplifier"),
        resultSet.getInt("quantity"),
        resultSet.getLong("unit_price"),
        resultSet.getString("group_buy_voucher_code"),
        resultSet.getString("group_buy_voucher_status"),
        groupBuyVoucherConsumedAt == null ? null : groupBuyVoucherConsumedAt.toLocalDateTime(),
        claimToken);
  }

  private OrderView readMarketOrderView(ResultSet resultSet, long userId, String claimTokenOverride)
      throws SQLException {
    long tradeId = resultSet.getLong("trade_id");
    long listingId = resultSet.getLong("listing_id");
    String currencyRaw = resultSet.getString("currency");
    long unitPrice = resultSet.getLong("unit_price");
    int tradeQuantity = resultSet.getInt("trade_quantity");
    long totalPrice = resultSet.getLong("total_price");
    long buyerTotal = resultSet.getLong("buyer_total");
    String itemMaterial = resultSet.getString("item_material");
    String remark = resultSet.getString("remark");
    String buyerUuidRaw = resultSet.getString("buyer_uuid");
    UUID buyerUuid = buyerUuidRaw == null ? null : UUID.fromString(buyerUuidRaw);
    Timestamp refundDeadline = resultSet.getTimestamp("refund_deadline");
    Timestamp refundedAt = resultSet.getTimestamp("refunded_at");
    Timestamp deliveredAt = resultSet.getTimestamp("delivered_at");
    String tradeStatus = resultSet.getString("trade_status");
    String deliveryStatus = resultSet.getString("delivery_status");
    String claimToken = claimTokenOverride != null ? claimTokenOverride : resultSet.getString("claim_token");
    String status;
    if (tradeStatus == null || tradeStatus.isBlank()) {
      status = "DELIVERED".equalsIgnoreCase(deliveryStatus) || deliveredAt != null
          ? "DELIVERED"
          : "PENDING";
    } else {
      status = tradeStatus.toUpperCase(Locale.ROOT);
    }
    if ("WAIT_CLAIM".equalsIgnoreCase(deliveryStatus)) {
      status = "WAIT_CLAIM";
    }

    long totalAmount = buyerTotal > 0 ? buyerTotal : totalPrice;
    String title = itemMaterial == null || itemMaterial.isBlank()
        ? "Market Listing Item"
        : itemMaterial;

    return new OrderView(
        tradeId,
        "MKT-" + tradeId,
        userId,
        buyerUuid,
        CurrencyType.valueOf(currencyRaw),
        totalAmount,
        status,
        resultSet.getTimestamp("created_at").toLocalDateTime(),
        deliveredAt == null ? null : deliveredAt.toLocalDateTime(),
        refundedAt == null ? null : refundedAt.toLocalDateTime(),
        refundDeadline == null ? null : refundDeadline.toLocalDateTime(),
        "LIST-" + listingId,
        title,
        remark,
        "MARKET",
        itemMaterial,
        null,
        null,
        null,
        null,
        tradeQuantity,
        unitPrice > 0 ? unitPrice : totalPrice,
        null,
        null,
        null,
        claimToken);
  }

  private String ensureOrderClaimToken(Connection connection, long orderId, String status, String claimToken)
      throws SQLException {
    if (!"WAIT_CLAIM".equalsIgnoreCase(status)) {
      return claimToken;
    }
    if (claimToken != null && !claimToken.isBlank()) {
      return claimToken;
    }
    return ClaimTokenRepository.ensureOrderToken(connection, orderId, sqlProvider.forUpdateClause());
  }

  private String ensureMarketClaimToken(Connection connection, long tradeId, String status, String claimToken)
      throws SQLException {
    if (!"WAIT_CLAIM".equalsIgnoreCase(status)) {
      return claimToken;
    }
    if (claimToken != null && !claimToken.isBlank()) {
      return claimToken;
    }
    return ClaimTokenRepository.ensureMarketTradeToken(
        connection,
        tradeId,
        sqlProvider.forUpdateClause());
  }

  private record ExistingOrder(
      String orderNo,
      String currency,
      long totalAmount,
      String status,
      LocalDateTime refundDeadline,
      String groupBuyVoucherCode,
      String groupBuyVoucherStatus,
      LocalDateTime groupBuyVoucherConsumedAt) {
  }

  private record OrderRow(
      long id,
      String orderNo,
      String currency,
      long totalAmount,
      String status,
      LocalDateTime refundDeadline,
      long productId,
      int quantity,
      String groupBuyVoucherCode,
      String groupBuyVoucherStatus) {
  }

  private record MarketOrderRow(
      long tradeId,
      long listingId,
      String currency,
      long unitPrice,
      int quantity,
      long totalPrice,
      long buyerTotal,
      String status,
      LocalDateTime refundDeadline) {
  }

  enum PlacementState {
    CREATED,
    EXISTING
  }

  enum DeliveryKind {
    COMMAND,
    GIVE_ITEM,
    POTION_EFFECT
  }

  enum DeliveryMode {
    IMMEDIATE,
    CLAIM
  }

  private record DeliveryTaskSpec(
      DeliveryKind kind,
      String commandText,
      String payloadJson,
      int quantity) {
  }

  record OrderPlacementResult(
      PlacementState state,
      String orderNo,
      CurrencyType currency,
      long totalAmount,
      String orderStatus,
      LocalDateTime refundDeadline,
      int cooldownSeconds,
      String groupBuyVoucherCode,
      String groupBuyVoucherStatus,
      LocalDateTime groupBuyVoucherConsumedAt) {
  }

  record OrderView(
      long id,
      String orderNo,
      long userId,
      UUID mcUuid,
      CurrencyType currency,
      long totalAmount,
      String status,
      LocalDateTime createdAt,
      LocalDateTime deliveredAt,
      LocalDateTime refundedAt,
      LocalDateTime refundDeadline,
      String productSku,
      String productTitle,
      String productRemark,
      String productType,
      String itemMaterial,
      Integer itemAmount,
      String effectType,
      Integer effectSeconds,
      Integer effectAmplifier,
      int quantity,
      long unitPrice,
      String groupBuyVoucherCode,
      String groupBuyVoucherStatus,
      LocalDateTime groupBuyVoucherConsumedAt,
      String claimToken) {
  }

  record AdminOrderView(
      OrderView order,
      String username,
      UUID boundUuid) {
  }

  private record GroupBuyVoucherRow(
      long id,
      String code,
      String status,
      String orderNo,
      long userId,
      String username,
      String productSku,
      String productTitle,
      LocalDateTime consumedAt) {
  }

  record GroupBuyVoucherConsumeResult(
      String code,
      String status,
      String orderNo,
      long userId,
      String username,
      String productSku,
      String productTitle,
      LocalDateTime consumedAt) {
  }

  record RefundResult(String orderNo, WalletService.WalletBalance balance) {
  }
}


