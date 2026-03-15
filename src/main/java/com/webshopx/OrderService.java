package com.webshopx;

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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

class OrderService {
  private static final String GROUP_BUY_VOUCHER_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

  private final JavaPlugin plugin;
  private final DatabaseManager databaseManager;
  private final Supplier<PluginSettings> settingsSupplier;
  private final ProductService productService;
  private final WalletService walletService;
  private final SecureRandom secureRandom;

  OrderService(
      JavaPlugin plugin,
      DatabaseManager databaseManager,
      Supplier<PluginSettings> settingsSupplier,
      ProductService productService,
      WalletService walletService) {
    this.plugin = plugin;
    this.databaseManager = databaseManager;
    this.settingsSupplier = settingsSupplier;
    this.productService = productService;
    this.walletService = walletService;
    this.secureRandom = new SecureRandom();
  }

  OrderPlacementResult placeOrder(long userId, long productId, int quantity, String idempotencyKey) {
    if (quantity < 1 || quantity > 64) {
      throw new ServiceException("invalid_quantity", "Quantity must be between 1 and 64");
    }

    ProductService.ProductView product = databaseManager.withConnection(
        connection -> productService.readActiveProduct(connection, productId, false));
    if (product.productType() == ProductService.ProductType.RECYCLE_ITEM) {
      return runSync(() -> placeRecycleOrder(userId, product, quantity, idempotencyKey));
    }

    int cooldownSeconds = normalizedOrderCooldownSeconds();
    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
    return databaseManager.inTransaction(connection ->
        placePurchaseOrderInTransaction(connection, userId, product, quantity, normalizedKey, cooldownSeconds));
  }

  private OrderPlacementResult placePurchaseOrderInTransaction(
      Connection connection,
      long userId,
      ProductService.ProductView product,
      int quantity,
      String idempotencyKey,
      int cooldownSeconds) throws SQLException {
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

    UUID playerUuid = readBoundUuidForUpdate(connection, userId);
    long totalAmount = Math.multiplyExact(product.price(), quantity);
    String orderNo = newOrderNo();
    boolean isGroupBuyVoucher = product.productType() == ProductService.ProductType.GROUP_BUY_VOUCHER;
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime refundDeadline = !isGroupBuyVoucher && cooldownSeconds > 0
        ? now.plusSeconds(cooldownSeconds)
        : null;
    String orderStatus = isGroupBuyVoucher ? "DELIVERED" : "PENDING";

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
        refundDeadline);
    long itemId = insertOrderItem(connection, orderId, product.id(), quantity, product.price());
    String groupBuyVoucherCode = null;
    String groupBuyVoucherStatus = null;
    LocalDateTime groupBuyVoucherConsumedAt = null;
    if (isGroupBuyVoucher) {
      groupBuyVoucherCode = insertGroupBuyVoucher(connection, orderId, userId, product.id());
      groupBuyVoucherStatus = "ISSUED";
    } else {
      String commandText = buildCommandText(product, quantity);
      LocalDateTime deliveryAt = refundDeadline == null ? now : refundDeadline;
      insertDelivery(connection, orderId, itemId, playerUuid, commandText, quantity, deliveryAt);
    }

    return new OrderPlacementResult(
        PlacementState.CREATED,
        orderNo,
        product.currency(),
        totalAmount,
        orderStatus,
        refundDeadline,
        isGroupBuyVoucher ? 0 : cooldownSeconds,
        groupBuyVoucherCode,
        groupBuyVoucherStatus,
        groupBuyVoucherConsumedAt);
  }

  private OrderPlacementResult placeRecycleOrder(
      long userId,
      ProductService.ProductView product,
      int quantity,
      String idempotencyKey) {
    if (product.productType() != ProductService.ProductType.RECYCLE_ITEM) {
      throw new ServiceException("invalid_product_type", "Product type is not recyclable");
    }
    if (product.itemMaterial() == null) {
      throw new ServiceException("invalid_product", "Recycle material is missing");
    }
    int packSize = product.itemAmount() == null ? 1 : product.itemAmount();
    int requiredAmount;
    try {
      requiredAmount = Math.multiplyExact(packSize, quantity);
    } catch (ArithmeticException exception) {
      throw new ServiceException("invalid_quantity", "Recycle quantity overflow");
    }
    if (requiredAmount <= 0) {
      throw new ServiceException("invalid_quantity", "Recycle amount must be positive");
    }

    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
    Material material = Material.matchMaterial(product.itemMaterial());
    if (material == null || material == Material.AIR) {
      throw new ServiceException("invalid_product", "Recycle material is invalid");
    }

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

      UUID playerUuid = readBoundUuidForUpdate(connection, userId);
      Player player = Bukkit.getPlayer(playerUuid);
      if (player == null || !player.isOnline()) {
        throw new ServiceException("player_offline", "Player must be online for recycle orders");
      }

      if (!hasEnoughItem(player, material, requiredAmount)) {
        throw new ServiceException("insufficient_item", "Not enough items to recycle");
      }

      String orderNo = newOrderNo();
      long totalAmount = Math.multiplyExact(product.price(), quantity);
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
            null);
        insertOrderItem(connection, orderId, product.id(), quantity, product.price());
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

  private String buildCommandText(ProductService.ProductView product, int quantity) {
    ProductService.ProductType productType = product.productType();
    if (productType == ProductService.ProductType.COMMAND) {
      return product.commandTemplate();
    }
    if (productType == ProductService.ProductType.GIVE_ITEM) {
      if (product.itemMaterial() == null) {
        throw new ServiceException("invalid_product", "Item material is missing");
      }
      int amount = Math.max(1, product.itemAmount() == null ? 1 : product.itemAmount());
      int totalAmount = amount * quantity;
      return "give %player% " + product.itemMaterial().toLowerCase(Locale.ROOT) + " " + totalAmount;
    }
    if (productType == ProductService.ProductType.POTION_EFFECT) {
      if (product.effectType() == null) {
        throw new ServiceException("invalid_product", "Potion effect type is missing");
      }
      int seconds = product.effectSeconds() == null ? 30 : product.effectSeconds();
      long totalSeconds = Math.min(86_400L, (long) seconds * (long) quantity);
      int amplifier = product.effectAmplifier() == null ? 0 : product.effectAmplifier();
      return "effect give %player% "
          + product.effectType()
          + " "
          + totalSeconds
          + " "
          + amplifier
          + " true";
    }
    throw new ServiceException("invalid_product_type", "Unsupported product type");
  }

  private ExistingOrder readExistingOrder(Connection connection, long userId, String idempotencyKey)
      throws SQLException {
    String sql = """
        SELECT o.order_no, o.currency, o.total_amount, o.status, o.refund_deadline,
               gv.code AS group_buy_voucher_code, gv.status AS group_buy_voucher_status,
               gv.consumed_at AS group_buy_voucher_consumed_at
        FROM orders o
        LEFT JOIN group_buy_vouchers gv ON gv.order_id = o.id
        WHERE o.user_id = ? AND o.idempotency_key = ?
        FOR UPDATE
        """;
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
      String status,
      String idempotencyKey,
      LocalDateTime refundDeadline) throws SQLException {
    String sql = """
        INSERT INTO orders (
          order_no,
          user_id,
          mc_uuid,
          currency,
          total_amount,
          status,
          idempotency_key,
          refund_deadline
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
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
      if (refundDeadline == null) {
        statement.setTimestamp(8, null);
      } else {
        statement.setTimestamp(8, Timestamp.valueOf(refundDeadline));
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
        if (isDuplicateVoucherCode(exception)) {
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
      String commandText,
      int quantity,
      LocalDateTime nextRetryAt) throws SQLException {
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
      statement.setTimestamp(6, Timestamp.valueOf(nextRetryAt));
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

  private boolean isDuplicateVoucherCode(SQLException exception) {
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
    for (ItemStack itemStack : player.getInventory().getContents()) {
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

  private OrderPlacementResult runSync(java.util.concurrent.Callable<OrderPlacementResult> task) {
    if (Bukkit.isPrimaryThread()) {
      try {
        return task.call();
      } catch (Exception exception) {
        if (exception instanceof RuntimeException runtimeException) {
          throw runtimeException;
        }
        throw new ServiceException("internal_error", "Sync task failed");
      }
    }
    try {
      return Bukkit.getScheduler()
          .callSyncMethod(plugin, task)
          .get(8, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ServiceException("sync_interrupted", "Sync task interrupted");
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new ServiceException("internal_error", "Sync task failed");
    } catch (TimeoutException exception) {
      throw new ServiceException("sync_timeout", "Sync task timed out");
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

  private List<OrderView> listOfficialOrders(
      Connection connection,
      long userId,
      int pageSize,
      Long cursor) throws SQLException {
    String cursorSql = cursor == null ? "" : " AND o.id < ?";
    String sql = """
        SELECT o.id, o.order_no, o.user_id, o.mc_uuid, o.currency, o.total_amount, o.status,
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
          results.add(readOrderView(resultSet));
        }
      }
      return results;
    }
  }

  private List<OrderView> listMarketOrders(
      Connection connection,
      long userId,
      int pageSize,
      LocalDateTime cutoff) throws SQLException {
    String cutoffSql = cutoff == null ? "" : " AND mt.created_at < ?";
    String sql = """
        SELECT mt.id AS trade_id, mt.listing_id, mt.currency, mt.total_price, mt.buyer_total,
               mt.status AS trade_status, mt.refund_deadline, mt.refunded_at, mt.created_at,
               ml.item_material, ml.remark, ml.quantity, ml.buyer_uuid,
               md.status AS delivery_status, md.delivered_at
        FROM market_trades mt
        JOIN market_listings ml ON ml.id = mt.listing_id
        LEFT JOIN market_item_deliveries md
          ON md.listing_id = ml.id AND md.delivery_type = 'SALE'
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
          results.add(readMarketOrderView(resultSet, userId));
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
      String orderNo) {
    int pageSize = Math.min(Math.max(1, limit), 300);
    String normalizedStatus = status == null ? null : status.trim().toUpperCase(Locale.ROOT);
    String normalizedOrderNo = orderNo == null ? null : orderNo.trim();
    return databaseManager.withConnection(connection -> {
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
      if (cursor != null) {
        clauses.add("o.id < ?");
      }

      String sql = """
          SELECT o.id, o.order_no, o.user_id, o.mc_uuid, o.currency, o.total_amount, o.status,
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
          WHERE """ + String.join(" AND ", clauses) + """
          ORDER BY o.id DESC
          LIMIT ?
          """;
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
        if (cursor != null) {
          statement.setLong(index++, cursor);
        }
        statement.setInt(index, pageSize);

        List<AdminOrderView> results = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            OrderView view = readOrderView(resultSet);
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
    });
  }

  GroupBuyVoucherConsumeResult consumeGroupBuyVoucher(long adminUserId, String rawCode) {
    String code = normalizeGroupBuyVoucherCode(rawCode);
    return databaseManager.inTransaction(connection -> {
      GroupBuyVoucherRow row = readGroupBuyVoucherForUpdate(connection, code);
      if (row == null) {
        throw new ServiceException("voucher_missing", "Group-buy voucher not found");
      }
      if (!"ISSUED".equalsIgnoreCase(row.status())) {
        throw new ServiceException("voucher_unavailable", "Group-buy voucher is already consumed");
      }

      String updateSql = """
          UPDATE group_buy_vouchers
          SET status = 'CONSUMED',
              consumed_by_admin_id = ?,
              consumed_at = NOW()
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
      if ("REFUNDED".equalsIgnoreCase(row.status())) {
        throw new ServiceException("already_refunded", "Order has already been refunded");
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
          SET status = 'REFUNDED', refunded_at = NOW()
          WHERE id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(updateOrderSql)) {
        statement.setLong(1, row.id());
        statement.executeUpdate();
      }

      String cancelDeliverySql = """
          UPDATE delivery_queue
          SET status = 'CANCELLED', last_error = 'Refunded'
          WHERE order_id = ? AND status = 'PENDING'
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

  private GroupBuyVoucherRow readGroupBuyVoucherForUpdate(Connection connection, String code)
      throws SQLException {
    String sql = """
        SELECT gv.id, gv.code, gv.status, gv.consumed_at,
               o.order_no, o.user_id, u.username, p.sku, p.title
        FROM group_buy_vouchers gv
        JOIN orders o ON o.id = gv.order_id
        JOIN web_users u ON u.id = gv.user_id
        JOIN products p ON p.id = gv.product_id
        WHERE gv.code = ?
        FOR UPDATE
        """;
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
    MarketOrderRow order = databaseManager.inTransaction(connection -> {
      MarketOrderRow row = readMarketTradeForRefund(connection, userId, tradeId);
      if (row == null) {
        throw new ServiceException("order_missing", "Order not found");
      }
      if ("REFUNDED".equalsIgnoreCase(row.status())) {
        throw new ServiceException("already_refunded", "Order has already been refunded");
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
          SET status = 'REFUNDED', refunded_at = NOW()
          WHERE id = ? AND status = 'PENDING'
          """;
      try (PreparedStatement statement = connection.prepareStatement(updateTradeSql)) {
        statement.setLong(1, row.tradeId());
        statement.executeUpdate();
      }

      String cancelDeliverySql = """
          UPDATE market_item_deliveries
          SET status = 'CANCELLED', last_error = 'Refunded'
          WHERE listing_id = ?
            AND delivery_type = 'SALE'
            AND status = 'PENDING'
          """;
      try (PreparedStatement statement = connection.prepareStatement(cancelDeliverySql)) {
        statement.setLong(1, row.listingId());
        statement.executeUpdate();
      }

      String restoreListingSql = """
          UPDATE market_listings
          SET status = 'ACTIVE',
              buyer_user_id = NULL,
              buyer_uuid = NULL,
              sold_at = NULL
          WHERE id = ?
            AND buyer_user_id = ?
            AND status = 'SOLD'
          """;
      try (PreparedStatement statement = connection.prepareStatement(restoreListingSql)) {
        statement.setLong(1, row.listingId());
        statement.setLong(2, userId);
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
    String sql = """
        SELECT id, order_no, currency, total_amount, status, refund_deadline
        FROM orders
        WHERE user_id = ? AND order_no = ?
        FOR UPDATE
        """;
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
            refundDeadline == null ? null : refundDeadline.toLocalDateTime());
      }
    }
  }

  private MarketOrderRow readMarketTradeForRefund(Connection connection, long userId, long tradeId)
      throws SQLException {
    String sql = """
        SELECT id, listing_id, currency, total_price, buyer_total, status, refund_deadline
        FROM market_trades
        WHERE id = ? AND buyer_user_id = ?
        FOR UPDATE
        """;
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
            resultSet.getLong("total_price"),
            resultSet.getLong("buyer_total"),
            resultSet.getString("status"),
            refundDeadline == null ? null : refundDeadline.toLocalDateTime());
      }
    }
  }

  private OrderView readOrderView(ResultSet resultSet) throws SQLException {
    Timestamp deliveredAt = resultSet.getTimestamp("delivered_at");
    Timestamp refundDeadline = resultSet.getTimestamp("refund_deadline");
    Timestamp refundedAt = resultSet.getTimestamp("refunded_at");
    Timestamp groupBuyVoucherConsumedAt = resultSet.getTimestamp("group_buy_voucher_consumed_at");
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
        groupBuyVoucherConsumedAt == null ? null : groupBuyVoucherConsumedAt.toLocalDateTime());
  }

  private OrderView readMarketOrderView(ResultSet resultSet, long userId) throws SQLException {
    long tradeId = resultSet.getLong("trade_id");
    long listingId = resultSet.getLong("listing_id");
    String currencyRaw = resultSet.getString("currency");
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
    String status;
    if (tradeStatus == null || tradeStatus.isBlank()) {
      status = "DELIVERED".equalsIgnoreCase(deliveryStatus) || deliveredAt != null
          ? "DELIVERED"
          : "PENDING";
    } else {
      status = tradeStatus.toUpperCase(Locale.ROOT);
    }

    long totalAmount = buyerTotal > 0 ? buyerTotal : totalPrice;
    String title = itemMaterial == null || itemMaterial.isBlank()
        ? "玩家市场商品"
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
        resultSet.getInt("quantity"),
        totalPrice,
        null,
        null,
        null);
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
      LocalDateTime refundDeadline) {
  }

  private record MarketOrderRow(
      long tradeId,
      long listingId,
      String currency,
      long totalPrice,
      long buyerTotal,
      String status,
      LocalDateTime refundDeadline) {
  }

  enum PlacementState {
    CREATED,
    EXISTING
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
      LocalDateTime groupBuyVoucherConsumedAt) {
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

