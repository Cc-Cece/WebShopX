package com.webshopx;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

class OrderService {
  private final JavaPlugin plugin;
  private final DatabaseManager databaseManager;
  private final ProductService productService;
  private final WalletService walletService;
  private final SecureRandom secureRandom;

  OrderService(
      JavaPlugin plugin,
      DatabaseManager databaseManager,
      ProductService productService,
      WalletService walletService) {
    this.plugin = plugin;
    this.databaseManager = databaseManager;
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

    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
    return databaseManager.inTransaction(connection ->
        placePurchaseOrderInTransaction(connection, userId, product, quantity, normalizedKey));
  }

  private OrderPlacementResult placePurchaseOrderInTransaction(
      Connection connection,
      long userId,
      ProductService.ProductView product,
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
    long totalAmount = Math.multiplyExact(product.price(), quantity);
    String orderNo = newOrderNo();
    String commandText = buildCommandText(product, quantity);

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
        "PENDING",
        idempotencyKey);
    long itemId = insertOrderItem(connection, orderId, product.id(), quantity, product.price());
    insertDelivery(connection, orderId, itemId, playerUuid, commandText, quantity);

    return new OrderPlacementResult(PlacementState.CREATED, orderNo, product.currency(), totalAmount);
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
            existingOrder.totalAmount());
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
            normalizedKey);
        insertOrderItem(connection, orderId, product.id(), quantity, product.price());
        return new OrderPlacementResult(
            PlacementState.CREATED,
            orderNo,
            product.currency(),
            totalAmount);
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
      String status,
      String idempotencyKey) throws SQLException {
    String sql = """
        INSERT INTO orders (order_no, user_id, mc_uuid, currency, total_amount, status, idempotency_key)
        VALUES (?, ?, ?, ?, ?, ?, ?)
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
