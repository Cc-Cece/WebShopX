package com.webshopx;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    if (isRecycleProductType(product.productType())) {
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
    ProductService.ProductPriceQuote priceQuote = productService.quoteOrderPrice(product, quantity);
    long unitPrice = priceQuote.averageUnitPrice();
    long totalAmount = priceQuote.totalAmount();
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
      ProductService.ProductType recycleType = product.productType();
      if (!isRecycleProductType(recycleType)) {
        throw new ServiceException("invalid_product_type", "Product type is not recyclable");
      }
      if (isAdvancedRecycleProductType(recycleType) && !settingsSupplier.get().advancedRecycleEnabled()) {
        throw new ServiceException("feature_disabled", "Advanced recycle is disabled");
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

      String orderNo = newOrderNo();
      ProductService.ProductPriceQuote priceQuote = productService.quoteOrderPrice(product, quantity);
      long unitPrice = priceQuote.averageUnitPrice();
      long totalAmount = priceQuote.totalAmount();
      Material material = resolveVanillaMaterial(product.itemMaterial());
      int removedAmount = 0;
      int materialCountBefore = material == null ? 0 : countItems(player, material);
      if (recycleType == ProductService.ProductType.RECYCLE_ITEM) {
        if (material == null || material == Material.AIR) {
          throw new ServiceException("invalid_product", "Recycle material is invalid");
        }
        if (materialCountBefore < requiredAmount) {
          throw new ServiceException("insufficient_item", "Not enough items to recycle");
        }
        boolean removed = removeItems(player, material, requiredAmount);
        if (!removed) {
          throw new ServiceException("insufficient_item", "Failed to remove recycle items");
        }
        removedAmount = requiredAmount;
      } else {
        String command = renderRecycleCommand(
            product.commandTemplate(),
            player.getName(),
            requiredAmount,
            orderNo);
        if (!dispatchRecycleCommand(command)) {
          throw new ServiceException("recycle_command_failed", "Recycle command execution returned false");
        }
        if (material != null && material != Material.AIR) {
          int materialCountAfter = countItems(player, material);
          int deducted = Math.max(0, materialCountBefore - materialCountAfter);
          if (deducted < requiredAmount) {
            throw new ServiceException("insufficient_item", "Recycle command did not remove enough items");
          }
          removedAmount = deducted;
        }
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
        if (material != null && removedAmount > 0) {
          restoreItems(player, material, removedAmount);
        }
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
    if (productType == ProductService.ProductType.GIVE_CUSTOM_ITEM) {
      return new DeliveryTaskSpec(
          DeliveryKind.COMMAND,
          product.commandTemplate(),
          null,
          quantity);
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
            WHEN stock_remaining + ? > item_amount THEN item_amount
            ELSE stock_remaining + ?
          END
        WHERE id = ?
          AND stock_remaining IS NOT NULL
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, quantity);
      statement.setInt(2, quantity);
      statement.setLong(3, productId);
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
        SET used_count = CASE
              WHEN used_count - ? < 0 THEN 0
              ELSE used_count - ?
            END,
            updated_at = CURRENT_TIMESTAMP
        WHERE product_id = ? AND user_id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
      statement.setInt(1, quantity);
      statement.setInt(2, quantity);
      statement.setLong(3, productId);
      statement.setLong(4, userId);
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
      case GIVE_ITEM,
          GIVE_CUSTOM_ITEM,
          RECYCLE_ITEM,
          RECYCLE_COMMAND_ITEM,
          RECYCLE_CUSTOM_ITEM,
          GROUP_BUY_VOUCHER -> DeliveryMode.IMMEDIATE;
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
    return countItems(player, material) >= requiredAmount;
  }

  private int countItems(Player player, Material material) {
    if (player == null || material == null || material == Material.AIR) {
      return 0;
    }
    int count = 0;
    ItemStack[] contents = player.getInventory().getContents();
    for (ItemStack itemStack : contents) {
      if (itemStack == null || itemStack.getType() != material) {
        continue;
      }
      count += itemStack.getAmount();
    }
    return count;
  }

  private Material resolveVanillaMaterial(String rawMaterial) {
    if (rawMaterial == null || rawMaterial.isBlank()) {
      return null;
    }
    String normalized = rawMaterial.trim();
    String key = normalized.toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
    Material material = Material.matchMaterial(key);
    if (material == null && key.startsWith("BLOCK_OF_") && key.length() > "BLOCK_OF_".length()) {
      material = Material.matchMaterial(key.substring("BLOCK_OF_".length()) + "_BLOCK");
    }
    if (material == null) {
      material = Material.matchMaterial(normalized.toLowerCase(Locale.ROOT));
    }
    return material;
  }

  private String renderRecycleCommand(
      String template,
      String playerName,
      int quantity,
      String orderNo) {
    String rendered = String.valueOf(template == null ? "" : template)
        .replace("{player}", playerName)
        .replace("%player%", playerName)
        .replace("%amount%", Integer.toString(quantity))
        .replace("{amount}", Integer.toString(quantity))
        .replace("{quantity}", Integer.toString(quantity))
        .replace("%quantity%", Integer.toString(quantity))
        .replace("%order%", orderNo);
    if (rendered.startsWith("/")) {
      rendered = rendered.substring(1);
    }
    return rendered.trim();
  }

  private boolean dispatchRecycleCommand(String command) {
    if (command == null || command.isBlank()) {
      return false;
    }
    try {
      if (Bukkit.isPrimaryThread()) {
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
      }
      return schedulerBridge
          .supplyGlobal(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command))
          .join();
    } catch (Exception exception) {
      throw new ServiceException("recycle_command_failed", "Recycle command execution failed");
    }
  }

  private boolean isRecycleProductType(ProductService.ProductType productType) {
    return productType == ProductService.ProductType.RECYCLE_ITEM
        || productType == ProductService.ProductType.RECYCLE_COMMAND_ITEM
        || productType == ProductService.ProductType.RECYCLE_CUSTOM_ITEM;
  }

  private boolean isAdvancedRecycleProductType(ProductService.ProductType productType) {
    return productType == ProductService.ProductType.RECYCLE_COMMAND_ITEM
        || productType == ProductService.ProductType.RECYCLE_CUSTOM_ITEM;
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
               (SELECT COUNT(*) FROM delivery_queue dq WHERE dq.order_id = o.id) AS delivery_task_count,
               (SELECT COUNT(*) FROM delivery_queue dq WHERE dq.order_id = o.id AND dq.status = 'WAIT_CLAIM') AS wait_claim_delivery_count,
               (SELECT COUNT(*) FROM delivery_queue dq WHERE dq.order_id = o.id AND dq.status NOT IN ('DELIVERED', 'CANCELLED')) AS open_delivery_count,
               (SELECT COUNT(*) FROM mailbox_items mi WHERE mi.user_id = o.user_id AND mi.source_type = 'ORDER' AND mi.source_ref = o.order_no AND mi.status = 'PENDING') AS pending_mailbox_count,
               (
                 (SELECT COALESCE(SUM(dq.delivered_quantity), 0) FROM delivery_queue dq WHERE dq.order_id = o.id) +
                 (SELECT COALESCE(SUM(mi.delivered_quantity), 0) FROM mailbox_items mi WHERE mi.user_id = o.user_id AND mi.source_type = 'ORDER' AND mi.source_ref = o.order_no)
               ) AS earned_quantity,
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
               md.status AS delivery_status, md.delivered_at, md.delivered_quantity
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
          int pendingMailboxCount = countPendingMailboxItems(connection, userId, "MARKET", "MKT-" + tradeId);
          int deliveredMailboxQuantity =
              countDeliveredMailboxQuantity(connection, userId, "MARKET", "MKT-" + tradeId);
          results.add(readMarketOrderView(
              resultSet,
              userId,
              ensuredToken,
              pendingMailboxCount,
              deliveredMailboxQuantity));
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
        if ("GIVE_ITEM".equalsIgnoreCase(normalizedProductType)) {
          clauses.add("(p.product_type = ? OR p.product_type = 'GIVE_CUSTOM_ITEM')");
        } else if ("RECYCLE_ITEM".equalsIgnoreCase(normalizedProductType)) {
          clauses.add("(p.product_type = ? OR p.product_type = 'RECYCLE_CUSTOM_ITEM')");
        } else {
          clauses.add("p.product_type = ?");
        }
      }
      if (cursor != null) {
        clauses.add("o.id < ?");
      }

      String sql = """
          SELECT o.id, o.order_no, o.user_id, o.mc_uuid, o.currency, o.total_amount, o.status,
                 o.claim_token,
                 o.created_at, o.delivered_at, o.refund_deadline, o.refunded_at,
                 (SELECT COUNT(*) FROM delivery_queue dq WHERE dq.order_id = o.id) AS delivery_task_count,
                 (SELECT COUNT(*) FROM delivery_queue dq WHERE dq.order_id = o.id AND dq.status = 'WAIT_CLAIM') AS wait_claim_delivery_count,
                 (SELECT COUNT(*) FROM delivery_queue dq WHERE dq.order_id = o.id AND dq.status NOT IN ('DELIVERED', 'CANCELLED')) AS open_delivery_count,
                 (SELECT COUNT(*) FROM mailbox_items mi WHERE mi.user_id = o.user_id AND mi.source_type = 'ORDER' AND mi.source_ref = o.order_no AND mi.status = 'PENDING') AS pending_mailbox_count,
                 (
                   (SELECT COALESCE(SUM(dq.delivered_quantity), 0) FROM delivery_queue dq WHERE dq.order_id = o.id) +
                   (SELECT COALESCE(SUM(mi.delivered_quantity), 0) FROM mailbox_items mi WHERE mi.user_id = o.user_id AND mi.source_type = 'ORDER' AND mi.source_ref = o.order_no)
                 ) AS earned_quantity,
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
    RefundExecution execution = databaseManager.inTransaction(connection -> {
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
      RefundPlan refundPlan = calculateOfficialRefundPlan(connection, row, userId);
      if (refundPlan.refundAmount() <= 0L || refundPlan.refundQuantity() <= 0) {
        throw new ServiceException("refund_not_allowed", "Order has no refundable amount");
      }
      claimOfficialRefund(connection, row);

      boolean refundApplied = walletService.applyDelta(
          connection,
          userId,
          CurrencyType.valueOf(row.currency()),
          refundPlan.refundAmount(),
          "ORDER_REFUND",
          row.orderNo() + ":refund",
          false);
      if (!refundApplied) {
        throw new ServiceException("already_refunded", "Order refund has already been applied");
      }

      restoreProductStock(connection, row.productId(), refundPlan.refundQuantity());
      reducePersonalLimitUsage(connection, row.productId(), userId, refundPlan.refundQuantity());

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

      return new RefundExecution(row.orderNo(), refundPlan);
    });

    WalletService.WalletBalance balance = walletService.getBalance(userId);
    return new RefundResult(
        execution.orderNo(),
        execution.refundPlan().refundAmount(),
        execution.refundPlan().refundQuantity(),
        execution.refundPlan().earnedQuantity(),
        balance);
  }

  DiscardResult discardOrder(long userId, String orderNo) {
    if (orderNo == null || orderNo.isBlank()) {
      throw new ServiceException("order_missing", "Order number is required");
    }
    String normalizedOrderNo = orderNo.trim();
    if (normalizedOrderNo.regionMatches(true, 0, "MKT-", 0, 4)) {
      return discardMarketOrder(userId, normalizedOrderNo);
    }
    return databaseManager.inTransaction(connection -> {
      OrderRow row = readOrderForRefund(connection, userId, normalizedOrderNo);
      if (row == null) {
        throw new ServiceException("order_missing", "Order not found");
      }
      if ("REFUNDED".equalsIgnoreCase(row.status())) {
        throw new ServiceException("already_refunded", "Order has already been refunded");
      }
      if ("CANCELLED".equalsIgnoreCase(row.status())) {
        throw new ServiceException("already_cancelled", "Order has already been cancelled");
      }
      if (isOfficialRefundAllowed(row)) {
        throw new ServiceException("refund_available", "Order is still refundable");
      }
      if (!hasDiscardableOfficialAssets(connection, row, userId)) {
        throw new ServiceException("discard_not_allowed", "Order has nothing pending to discard");
      }

      String updateOrderSql = """
          UPDATE orders
          SET status = 'CANCELLED', claim_token = NULL
          WHERE id = ?
            AND status <> 'REFUNDED'
            AND status <> 'CANCELLED'
          """;
      try (PreparedStatement statement = connection.prepareStatement(updateOrderSql)) {
        statement.setLong(1, row.id());
        if (statement.executeUpdate() <= 0) {
          throw new ServiceException("discard_not_allowed", "Order cannot be discarded");
        }
      }

      String cancelDeliverySql = """
          UPDATE delivery_queue
          SET status = 'CANCELLED', last_error = 'Discarded by buyer'
          WHERE order_id = ?
            AND status IN ('PENDING', 'WAIT_CLAIM')
          """;
      try (PreparedStatement statement = connection.prepareStatement(cancelDeliverySql)) {
        statement.setLong(1, row.id());
        statement.executeUpdate();
      }

      String cancelMailboxSql = """
          UPDATE mailbox_items
          SET status = 'CANCELLED', last_error = 'Discarded by buyer'
          WHERE user_id = ?
            AND source_type = 'ORDER'
            AND source_ref = ?
            AND status = 'PENDING'
          """;
      try (PreparedStatement statement = connection.prepareStatement(cancelMailboxSql)) {
        statement.setLong(1, userId);
        statement.setString(2, row.orderNo());
        statement.executeUpdate();
      }

      if (row.groupBuyVoucherCode() != null) {
        String updateVoucherSql = """
            UPDATE group_buy_vouchers
            SET status = 'CANCELLED'
            WHERE code = ?
              AND status = 'ISSUED'
            """;
        try (PreparedStatement statement = connection.prepareStatement(updateVoucherSql)) {
          statement.setString(1, row.groupBuyVoucherCode());
          statement.executeUpdate();
        }
      }
      return new DiscardResult(row.orderNo());
    });
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
      if ("PENDING".equalsIgnoreCase(row.status())
          || "WAIT_CLAIM".equalsIgnoreCase(row.status())
          || "DELIVERED".equalsIgnoreCase(row.status())
          || "COMPLETED".equalsIgnoreCase(row.status())) {
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
    RefundExecution execution = databaseManager.inTransaction(connection -> {
      MarketOrderRow row = readMarketTradeForRefund(connection, userId, tradeId);
      if (row == null) {
        throw new ServiceException("order_missing", "Order not found");
      }
      if ("REFUNDED".equalsIgnoreCase(row.status())) {
        throw new ServiceException("already_refunded", "Order has already been refunded");
      }
      validateMarketRefund(row);
      RefundPlan refundPlan = calculateMarketRefundPlan(connection, row, userId, orderNo);
      if (refundPlan.refundAmount() <= 0L || refundPlan.refundQuantity() <= 0) {
        throw new ServiceException("refund_not_allowed", "Order has no refundable amount");
      }
      claimMarketRefund(connection, row);

      boolean refundApplied = walletService.applyDelta(
          connection,
          userId,
          CurrencyType.valueOf(row.currency()),
          refundPlan.refundAmount(),
          "ORDER_REFUND",
          orderNo + ":refund",
          false);
      if (!refundApplied) {
        throw new ServiceException("already_refunded", "Order refund has already been applied");
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
          SET quantity_total = CASE
                WHEN quantity_total < quantity + ? THEN quantity + ?
                ELSE quantity_total
              END,
              quantity = quantity + ?,
              status = 'ACTIVE',
              buyer_user_id = NULL,
              buyer_uuid = NULL,
              sold_at = NULL
          WHERE id = ?
            AND status <> 'UNLISTED'
          """;
      try (PreparedStatement statement = connection.prepareStatement(restoreListingSql)) {
        statement.setInt(1, refundPlan.refundQuantity());
        statement.setInt(2, refundPlan.refundQuantity());
        statement.setInt(3, refundPlan.refundQuantity());
        statement.setLong(4, row.listingId());
        statement.executeUpdate();
      }

      return new RefundExecution(orderNo, refundPlan);
    });

    WalletService.WalletBalance balance = walletService.getBalance(userId);
    return new RefundResult(
        execution.orderNo(),
        execution.refundPlan().refundAmount(),
        execution.refundPlan().refundQuantity(),
        execution.refundPlan().earnedQuantity(),
        balance);
  }

  private DiscardResult discardMarketOrder(long userId, String orderNo) {
    long tradeId = parseMarketTradeId(orderNo);
    return databaseManager.inTransaction(connection -> {
      MarketOrderRow row = readMarketTradeForRefund(connection, userId, tradeId);
      if (row == null) {
        throw new ServiceException("order_missing", "Order not found");
      }
      if ("REFUNDED".equalsIgnoreCase(row.status())) {
        throw new ServiceException("already_refunded", "Order has already been refunded");
      }
      if ("CANCELLED".equalsIgnoreCase(row.status())) {
        throw new ServiceException("already_cancelled", "Order has already been cancelled");
      }
      if (isMarketRefundAllowed(row)) {
        throw new ServiceException("refund_available", "Order is still refundable");
      }
      if (!hasDiscardableMarketAssets(connection, row, userId, orderNo)) {
        throw new ServiceException("discard_not_allowed", "Order has nothing pending to discard");
      }

      String updateTradeSql = """
          UPDATE market_trades
          SET status = 'CANCELLED', claim_token = NULL
          WHERE id = ?
            AND status <> 'REFUNDED'
            AND status <> 'CANCELLED'
          """;
      try (PreparedStatement statement = connection.prepareStatement(updateTradeSql)) {
        statement.setLong(1, row.tradeId());
        if (statement.executeUpdate() <= 0) {
          throw new ServiceException("discard_not_allowed", "Order cannot be discarded");
        }
      }

      String cancelDeliverySql = """
          UPDATE market_item_deliveries
          SET status = 'CANCELLED', last_error = 'Discarded by buyer'
          WHERE trade_id = ?
            AND delivery_type = 'SALE'
            AND status IN ('PENDING', 'WAIT_CLAIM')
          """;
      try (PreparedStatement statement = connection.prepareStatement(cancelDeliverySql)) {
        statement.setLong(1, row.tradeId());
        statement.executeUpdate();
      }

      String cancelMailboxSql = """
          UPDATE mailbox_items
          SET status = 'CANCELLED', last_error = 'Discarded by buyer'
          WHERE user_id = ?
            AND source_type = 'MARKET'
            AND source_ref = ?
            AND status = 'PENDING'
          """;
      try (PreparedStatement statement = connection.prepareStatement(cancelMailboxSql)) {
        statement.setLong(1, userId);
        statement.setString(2, orderNo);
        statement.executeUpdate();
      }
      return new DiscardResult(orderNo);
    });
  }

  private boolean isOfficialRefundAllowed(OrderRow row) {
    try {
      validateOfficialRefund(row);
      return true;
    } catch (ServiceException exception) {
      return false;
    }
  }

  private boolean isMarketRefundAllowed(MarketOrderRow row) {
    try {
      validateMarketRefund(row);
      return true;
    } catch (ServiceException exception) {
      return false;
    }
  }

  private RefundPlan calculateOfficialRefundPlan(Connection connection, OrderRow row, long userId)
      throws SQLException {
    int totalQuantity = Math.max(1, row.quantity());
    if (row.groupBuyVoucherCode() != null) {
      String voucherStatus = String.valueOf(row.groupBuyVoucherStatus()).toUpperCase(Locale.ROOT);
      int earnedQuantity = "CONSUMED".equals(voucherStatus) ? totalQuantity : 0;
      int refundQuantity = Math.max(0, totalQuantity - earnedQuantity);
      return new RefundPlan(
          totalQuantity,
          earnedQuantity,
          refundQuantity,
          prorateAmount(row.totalAmount(), refundQuantity, totalQuantity));
    }
    int earnedQuantity = Math.min(
        totalQuantity,
        countDeliveredQuantity(connection, "delivery_queue", "order_id", row.id())
            + countDeliveredMailboxQuantity(connection, userId, "ORDER", row.orderNo()));
    int refundQuantity = Math.max(0, totalQuantity - earnedQuantity);
    return new RefundPlan(
        totalQuantity,
        earnedQuantity,
        refundQuantity,
        prorateAmount(row.totalAmount(), refundQuantity, totalQuantity));
  }

  private RefundPlan calculateMarketRefundPlan(
      Connection connection,
      MarketOrderRow row,
      long userId,
      String orderNo) throws SQLException {
    int totalQuantity = Math.max(1, row.quantity());
    int earnedQuantity = Math.min(
        totalQuantity,
        countMarketDeliveredQuantity(connection, row.tradeId())
            + countDeliveredMailboxQuantity(connection, userId, "MARKET", orderNo));
    int refundQuantity = Math.max(0, totalQuantity - earnedQuantity);
    long paidAmount = row.buyerTotal() > 0 ? row.buyerTotal() : row.totalPrice();
    return new RefundPlan(
        totalQuantity,
        earnedQuantity,
        refundQuantity,
        prorateAmount(paidAmount, refundQuantity, totalQuantity));
  }

  private int countDeliveredQuantity(Connection connection, String tableName, String keyColumn, long key)
      throws SQLException {
    if (!"delivery_queue".equals(tableName) || !"order_id".equals(keyColumn)) {
      throw new IllegalArgumentException("Unsupported delivered quantity source");
    }
    String sql = """
        SELECT COALESCE(SUM(delivered_quantity), 0) AS qty
        FROM delivery_queue
        WHERE order_id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, key);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? Math.max(0, resultSet.getInt("qty")) : 0;
      }
    }
  }

  private int countMarketDeliveredQuantity(Connection connection, long tradeId) throws SQLException {
    String sql = """
        SELECT COALESCE(SUM(delivered_quantity), 0) AS qty
        FROM market_item_deliveries
        WHERE trade_id = ?
          AND delivery_type = 'SALE'
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, tradeId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? Math.max(0, resultSet.getInt("qty")) : 0;
      }
    }
  }

  private int countDeliveredMailboxQuantity(
      Connection connection,
      long userId,
      String sourceType,
      String sourceRef) throws SQLException {
    String sql = """
        SELECT COALESCE(SUM(delivered_quantity), 0) AS qty
        FROM mailbox_items
        WHERE user_id = ?
          AND source_type = ?
          AND source_ref = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      statement.setString(2, sourceType);
      statement.setString(3, sourceRef);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? Math.max(0, resultSet.getInt("qty")) : 0;
      }
    }
  }

  private long prorateAmount(long totalAmount, int refundQuantity, int totalQuantity) {
    if (refundQuantity <= 0 || totalAmount <= 0L || totalQuantity <= 0) {
      return 0L;
    }
    if (refundQuantity >= totalQuantity) {
      return totalAmount;
    }
    return BigDecimal.valueOf(totalAmount)
        .multiply(BigDecimal.valueOf(refundQuantity))
        .divide(BigDecimal.valueOf(totalQuantity), 0, RoundingMode.HALF_UP)
        .longValue();
  }

  private boolean hasDiscardableOfficialAssets(Connection connection, OrderRow row, long userId)
      throws SQLException {
    if ("PENDING".equalsIgnoreCase(row.status()) || "WAIT_CLAIM".equalsIgnoreCase(row.status())) {
      return true;
    }
    if (row.groupBuyVoucherCode() != null && "ISSUED".equalsIgnoreCase(row.groupBuyVoucherStatus())) {
      return true;
    }
    String sql = """
        SELECT
          (SELECT COUNT(*) FROM delivery_queue dq WHERE dq.order_id = ? AND dq.status IN ('PENDING', 'WAIT_CLAIM')) +
          (SELECT COUNT(*) FROM mailbox_items mi WHERE mi.user_id = ? AND mi.source_type = 'ORDER' AND mi.source_ref = ? AND mi.status = 'PENDING') AS cnt
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, row.id());
      statement.setLong(2, userId);
      statement.setString(3, row.orderNo());
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() && resultSet.getInt("cnt") > 0;
      }
    }
  }

  private boolean hasDiscardableMarketAssets(
      Connection connection,
      MarketOrderRow row,
      long userId,
      String orderNo) throws SQLException {
    if ("PENDING".equalsIgnoreCase(row.status()) || "WAIT_CLAIM".equalsIgnoreCase(row.status())) {
      return true;
    }
    String sql = """
        SELECT
          (SELECT COUNT(*) FROM market_item_deliveries md WHERE md.trade_id = ? AND md.delivery_type = 'SALE' AND md.status IN ('PENDING', 'WAIT_CLAIM')) +
          (SELECT COUNT(*) FROM mailbox_items mi WHERE mi.user_id = ? AND mi.source_type = 'MARKET' AND mi.source_ref = ? AND mi.status = 'PENDING') AS cnt
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, row.tradeId());
      statement.setLong(2, userId);
      statement.setString(3, orderNo);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() && resultSet.getInt("cnt") > 0;
      }
    }
  }

  private int countPendingMailboxItems(Connection connection, long userId, String sourceType, String sourceRef)
      throws SQLException {
    String sql = """
        SELECT COUNT(*) AS cnt
        FROM mailbox_items
        WHERE user_id = ?
          AND source_type = ?
          AND source_ref = ?
          AND status = 'PENDING'
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      statement.setString(2, sourceType);
      statement.setString(3, sourceRef);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? Math.max(0, resultSet.getInt("cnt")) : 0;
      }
    }
  }

  private void claimOfficialRefund(Connection connection, OrderRow row) throws SQLException {
    String updateOrderSql = """
        UPDATE orders
        SET status = 'REFUNDED', refunded_at = CURRENT_TIMESTAMP, claim_token = NULL
        WHERE id = ?
          AND status = ?
          AND refunded_at IS NULL
        """;
    try (PreparedStatement statement = connection.prepareStatement(updateOrderSql)) {
      statement.setLong(1, row.id());
      statement.setString(2, row.status());
      if (statement.executeUpdate() == 0) {
        throw new ServiceException("already_refunded", "Order has already been refunded");
      }
    }
  }

  private void claimMarketRefund(Connection connection, MarketOrderRow row) throws SQLException {
    String updateTradeSql = """
        UPDATE market_trades
        SET status = 'REFUNDED', refunded_at = CURRENT_TIMESTAMP, claim_token = NULL
        WHERE id = ?
          AND status = ?
          AND refunded_at IS NULL
        """;
    try (PreparedStatement statement = connection.prepareStatement(updateTradeSql)) {
      statement.setLong(1, row.tradeId());
      statement.setString(2, row.status());
      if (statement.executeUpdate() == 0) {
        throw new ServiceException("already_refunded", "Order has already been refunded");
      }
    }
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
    String status = resultSet.getString("status");
    String voucherStatus = resultSet.getString("group_buy_voucher_status");
    int quantity = resultSet.getInt("quantity");
    int totalQuantity = Math.max(1, quantity);
    int earnedQuantity = Math.min(totalQuantity, Math.max(0, resultSet.getInt("earned_quantity")));
    if (resultSet.getString("group_buy_voucher_code") != null) {
      earnedQuantity = "CONSUMED".equalsIgnoreCase(voucherStatus) ? totalQuantity : 0;
    }
    int refundQuantity = Math.max(0, totalQuantity - earnedQuantity);
    long totalAmount = resultSet.getLong("total_amount");
    return new OrderView(
        resultSet.getLong("id"),
        resultSet.getString("order_no"),
        resultSet.getLong("user_id"),
        UUID.fromString(resultSet.getString("mc_uuid")),
        CurrencyType.valueOf(resultSet.getString("currency")),
        totalAmount,
        status,
        resolveOfficialDisplayStatus(
            status,
            voucherStatus,
            resultSet.getInt("delivery_task_count"),
            resultSet.getInt("wait_claim_delivery_count"),
            resultSet.getInt("open_delivery_count"),
            resultSet.getInt("pending_mailbox_count")),
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
        quantity,
        resultSet.getLong("unit_price"),
        prorateAmount(totalAmount, refundQuantity, totalQuantity),
        refundQuantity,
        earnedQuantity,
        resultSet.getString("group_buy_voucher_code"),
        voucherStatus,
        groupBuyVoucherConsumedAt == null ? null : groupBuyVoucherConsumedAt.toLocalDateTime(),
        claimToken);
  }

  private OrderView readMarketOrderView(
      ResultSet resultSet,
      long userId,
      String claimTokenOverride,
      int pendingMailboxCount,
      int deliveredMailboxQuantity)
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
    int totalQuantity = Math.max(1, tradeQuantity);
    int earnedQuantity = Math.min(
        totalQuantity,
        Math.max(0, resultSet.getInt("delivered_quantity"))
            + Math.max(0, deliveredMailboxQuantity));
    int refundQuantity = Math.max(0, totalQuantity - earnedQuantity);
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
        resolveDeliveryDisplayStatus(status, deliveryStatus, pendingMailboxCount),
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
        prorateAmount(totalAmount, refundQuantity, totalQuantity),
        refundQuantity,
        earnedQuantity,
        null,
        null,
        null,
        claimToken);
  }

  private String resolveOfficialDisplayStatus(
      String status,
      String voucherStatus,
      int deliveryTaskCount,
      int waitClaimDeliveryCount,
      int openDeliveryCount,
      int pendingMailboxCount) {
    if ("REFUNDED".equalsIgnoreCase(status) || "REFUNDED".equalsIgnoreCase(voucherStatus)) {
      return "REFUNDED";
    }
    if ("CANCELLED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(voucherStatus)) {
      return "CANCELLED";
    }
    if ("CONSUMED".equalsIgnoreCase(voucherStatus)) {
      return "CLAIMED";
    }
    if ("ISSUED".equalsIgnoreCase(voucherStatus)) {
      return "WAIT_CLAIM";
    }
    if ("WAIT_CLAIM".equalsIgnoreCase(status) || waitClaimDeliveryCount > 0 || pendingMailboxCount > 0) {
      return "WAIT_CLAIM";
    }
    if ("PENDING".equalsIgnoreCase(status) || openDeliveryCount > 0) {
      return "PENDING";
    }
    if ("DELIVERED".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status) || deliveryTaskCount > 0) {
      return "CLAIMED";
    }
    return status == null || status.isBlank() ? "PENDING" : status.toUpperCase(Locale.ROOT);
  }

  private String resolveDeliveryDisplayStatus(String status, String deliveryStatus, int pendingMailboxCount) {
    if ("REFUNDED".equalsIgnoreCase(status)) {
      return "REFUNDED";
    }
    if ("CANCELLED".equalsIgnoreCase(status)) {
      return "CANCELLED";
    }
    if ("WAIT_CLAIM".equalsIgnoreCase(status)
        || "WAIT_CLAIM".equalsIgnoreCase(deliveryStatus)
        || pendingMailboxCount > 0) {
      return "WAIT_CLAIM";
    }
    if ("PENDING".equalsIgnoreCase(status)) {
      return "PENDING";
    }
    if ("DELIVERED".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status)
        || "DELIVERED".equalsIgnoreCase(deliveryStatus)) {
      return "CLAIMED";
    }
    return status == null || status.isBlank() ? "PENDING" : status.toUpperCase(Locale.ROOT);
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
      String displayStatus,
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
      long refundAmount,
      int refundQuantity,
      int earnedQuantity,
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

  private record RefundPlan(
      int totalQuantity,
      int earnedQuantity,
      int refundQuantity,
      long refundAmount) {
  }

  private record RefundExecution(
      String orderNo,
      RefundPlan refundPlan) {
  }

  record RefundResult(
      String orderNo,
      long refundAmount,
      int refundQuantity,
      int earnedQuantity,
      WalletService.WalletBalance balance) {
  }

  record DiscardResult(String orderNo) {
  }

  DeliveryStatusResponse getDeliveryStatusForOrder(long userId, String orderNo) {
    if (orderNo == null || orderNo.isBlank()) {
      throw new ServiceException("order_missing", "Order number is required");
    }
    String normalized = orderNo.trim();
    boolean isMarket = normalized.regionMatches(true, 0, "MKT-", 0, 4);

    return databaseManager.withConnection(connection -> {
      UUID playerUuid = null;
      String orderStatus = "";
      String deliverySource = "ORDER";
      long orderId = -1L;
      long tradeIdParsed = -1L;

      if (isMarket) {
        try {
          tradeIdParsed = Long.parseLong(normalized.substring(4));
        } catch (NumberFormatException e) {
          throw new ServiceException("order_missing", "Order not found");
        }
        String queryTradeSql = "SELECT mt.id, mt.status, mt.buyer_uuid, ml.trade_mode "
            + "FROM market_trades mt JOIN market_listings ml ON ml.id = mt.listing_id "
            + "WHERE mt.id = ? AND mt.buyer_user_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(queryTradeSql)) {
          statement.setLong(1, tradeIdParsed);
          statement.setLong(2, userId);
          try (ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
              throw new ServiceException("order_missing", "Order not found");
            }
            String buyerUuidRaw = resultSet.getString("buyer_uuid");
            if (buyerUuidRaw != null && !buyerUuidRaw.isEmpty()) {
              playerUuid = UUID.fromString(buyerUuidRaw);
            }
            orderStatus = resultSet.getString("status");
            deliverySource = "AUCTION".equalsIgnoreCase(resultSet.getString("trade_mode"))
                ? "MARKET_AUCTION"
                : "MARKET_DIRECT";
          }
        }
      } else {
        String queryOrderSql = "SELECT id, status, mc_uuid FROM orders WHERE order_no = ? AND user_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(queryOrderSql)) {
          statement.setString(1, normalized);
          statement.setLong(2, userId);
          try (ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
              throw new ServiceException("order_missing", "Order not found");
            }
            orderId = resultSet.getLong("id");
            String mcUuidRaw = resultSet.getString("mc_uuid");
            if (mcUuidRaw != null && !mcUuidRaw.isEmpty()) {
              playerUuid = UUID.fromString(mcUuidRaw);
            }
            orderStatus = resultSet.getString("status");
          }
        }
      }

      boolean playerOnline = false;
      if (playerUuid != null) {
        try {
          Boolean online = schedulerBridge.supplyPlayer(playerUuid, Player::isOnline)
              .completeOnTimeout(false, 1L, TimeUnit.SECONDS)
              .exceptionally(ignored -> false)
              .join();
          playerOnline = online != null && online;
        } catch (Exception ignored) {}
      }

      MailboxDeliveryState mailboxState = readMailboxDeliveryState(
          connection,
          userId,
          isMarket ? "MARKET" : "ORDER",
          isMarket ? "MKT-" + tradeIdParsed : normalized);
      List<DeliveryTaskView> tasks = new ArrayList<>();
      if (isMarket) {
        String queryTasksSql = """
            SELECT id, status, retry_count, last_error, next_retry_at, delivered_at, claimed_at, created_at,
                   quantity, delivered_quantity, delivery_type, target_server_id
            FROM market_item_deliveries
            WHERE trade_id = ?
            ORDER BY id ASC
            """;
        try (PreparedStatement statement = connection.prepareStatement(queryTasksSql)) {
          statement.setLong(1, tradeIdParsed);
          try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
              Timestamp nextRetry = resultSet.getTimestamp("next_retry_at");
              Timestamp delivered = resultSet.getTimestamp("delivered_at");
              Timestamp claimed = resultSet.getTimestamp("claimed_at");
              Timestamp created = resultSet.getTimestamp("created_at");
              tasks.add(new DeliveryTaskView(
                  resultSet.getLong("id"),
                  resultSet.getString("status"),
                  resultSet.getInt("retry_count"),
                  resultSet.getString("last_error"),
                  nextRetry == null ? null : nextRetry.toLocalDateTime(),
                  delivered == null ? null : delivered.toLocalDateTime(),
                  claimed == null ? null : claimed.toLocalDateTime(),
                  created == null ? null : created.toLocalDateTime(),
                  resultSet.getInt("quantity"),
                  Math.max(0, resultSet.getInt("delivered_quantity")),
                  resultSet.getString("delivery_type"),
                  resultSet.getString("target_server_id"),
                  mailboxState.status(),
                  mailboxState.quantity(),
                  mailboxState.createdAt(),
                  mailboxState.claimedAt(),
                  mailboxState.reason()
              ));
            }
          }
        }
      } else {
        String queryTasksSql = """
            SELECT id, status, retry_count, last_error, next_retry_at, delivered_at, claimed_at, created_at,
                   quantity, delivered_quantity, delivery_kind, target_server_id
            FROM delivery_queue
            WHERE order_id = ?
            ORDER BY id ASC
            """;
        try (PreparedStatement statement = connection.prepareStatement(queryTasksSql)) {
          statement.setLong(1, orderId);
          try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
              Timestamp nextRetry = resultSet.getTimestamp("next_retry_at");
              Timestamp delivered = resultSet.getTimestamp("delivered_at");
              Timestamp claimed = resultSet.getTimestamp("claimed_at");
              Timestamp created = resultSet.getTimestamp("created_at");
              tasks.add(new DeliveryTaskView(
                  resultSet.getLong("id"),
                  resultSet.getString("status"),
                  resultSet.getInt("retry_count"),
                  resultSet.getString("last_error"),
                  nextRetry == null ? null : nextRetry.toLocalDateTime(),
                  delivered == null ? null : delivered.toLocalDateTime(),
                  claimed == null ? null : claimed.toLocalDateTime(),
                  created == null ? null : created.toLocalDateTime(),
                  resultSet.getInt("quantity"),
                  Math.max(0, resultSet.getInt("delivered_quantity")),
                  resultSet.getString("delivery_kind"),
                  resultSet.getString("target_server_id"),
                  mailboxState.status(),
                  mailboxState.quantity(),
                  mailboxState.createdAt(),
                  mailboxState.claimedAt(),
                  mailboxState.reason()
              ));
            }
          }
        }
      }

      return new DeliveryStatusResponse(normalized, orderStatus, deliverySource, playerOnline, tasks);
    });
  }

  private MailboxDeliveryState readMailboxDeliveryState(
      Connection connection,
      long userId,
      String sourceType,
      String sourceRef) throws SQLException {
    if (sourceRef == null || sourceRef.isBlank()) {
      return MailboxDeliveryState.empty();
    }
    String sql = """
        SELECT COUNT(*) AS total_count,
               COALESCE(SUM(quantity), 0) AS total_quantity,
               COALESCE(SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_count,
               COALESCE(SUM(CASE WHEN status = 'CLAIMED' THEN 1 ELSE 0 END), 0) AS claimed_count,
               MIN(created_at) AS created_at,
               MAX(claimed_at) AS claimed_at,
               MAX(reason) AS reason
        FROM mailbox_items
        WHERE user_id = ?
          AND source_type = ?
          AND source_ref = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      statement.setString(2, sourceType);
      statement.setString(3, sourceRef);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next() || resultSet.getInt("total_count") <= 0) {
          return MailboxDeliveryState.empty();
        }
        int pendingCount = resultSet.getInt("pending_count");
        int claimedCount = resultSet.getInt("claimed_count");
        String status = pendingCount > 0 ? "PENDING" : claimedCount > 0 ? "CLAIMED" : "UNKNOWN";
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        Timestamp claimedAt = resultSet.getTimestamp("claimed_at");
        return new MailboxDeliveryState(
            status,
            resultSet.getInt("total_quantity"),
            createdAt == null ? null : createdAt.toLocalDateTime(),
            claimedAt == null ? null : claimedAt.toLocalDateTime(),
            resultSet.getString("reason"));
      }
    }
  }

  record DeliveryStatusResponse(
      String orderNo,
      String status,
      String deliverySource,
      boolean playerOnline,
      List<DeliveryTaskView> deliveryTasks
  ) {}

  record DeliveryTaskView(
      long id,
      String status,
      int retryCount,
      String lastError,
      LocalDateTime nextRetryAt,
      LocalDateTime deliveredAt,
      LocalDateTime claimedAt,
      LocalDateTime createdAt,
      int quantity,
      int deliveredQuantity,
      String deliveryKind,
      String targetServerId,
      String mailboxStatus,
      int mailboxQuantity,
      LocalDateTime mailboxCreatedAt,
      LocalDateTime mailboxClaimedAt,
      String mailboxReason
  ) {}

  private record MailboxDeliveryState(
      String status,
      int quantity,
      LocalDateTime createdAt,
      LocalDateTime claimedAt,
      String reason) {
    static MailboxDeliveryState empty() {
      return new MailboxDeliveryState(null, 0, null, null, null);
    }
  }
}
