package com.webshopx;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;

class ProductService {
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 500;

  private final DatabaseManager databaseManager;

  ProductService(DatabaseManager databaseManager) {
    this.databaseManager = databaseManager;
  }

  void upsertSeeds(List<PluginSettings.ProductSeed> seeds) {
    if (seeds.isEmpty()) {
      return;
    }
    databaseManager.inTransaction(connection -> {
      for (PluginSettings.ProductSeed seed : seeds) {
        upsertSeed(connection, seed);
      }
      return null;
    });
  }

  List<ProductView> listActiveProducts() {
    return listProducts(false, 300);
  }

  List<ProductView> listProducts(boolean includeInactive, int requestedLimit) {
    int limit = normalizeLimit(requestedLimit);
    return databaseManager.withConnection(connection -> {
      String activeFilter = includeInactive
          ? ""
          : "WHERE active = TRUE "
              + "AND (publish_at IS NULL OR publish_at <= NOW()) "
              + "AND (unpublish_at IS NULL OR unpublish_at > NOW())";
      String sql = """
          SELECT id, sku, title, remark, currency, price, product_type, command_template,
                 item_material, item_amount, effect_type, effect_seconds, effect_amplifier,
                 publish_at, unpublish_at, active
          FROM products
          """ + activeFilter + " ORDER BY id ASC LIMIT ?";
      List<ProductView> products = new ArrayList<>();
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setInt(1, limit);
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            products.add(readProduct(resultSet));
          }
        }
      }
      return products;
    });
  }

  ProductView upsertProduct(AdminProductInput input) {
    validateAdminInput(input);
    return databaseManager.inTransaction(connection -> {
      String normalizedSku = normalizeSku(input.sku());
      ProductType productType = ProductType.fromRaw(input.productType());
      String normalizedRemark = normalizeRemark(input.remark());
      String normalizedCommand = normalizeCommandTemplate(input.commandTemplate(), productType);
      String normalizedItemMaterial = normalizeItemMaterial(input.itemMaterial(), productType);
      Integer normalizedItemAmount = normalizeItemAmount(input.itemAmount(), productType);
      String normalizedEffectType = normalizeEffectType(input.effectType(), productType);
      Integer normalizedEffectSeconds = normalizeEffectSeconds(input.effectSeconds(), productType);
      Integer normalizedEffectAmplifier = normalizeEffectAmplifier(
          input.effectAmplifier(),
          productType);
      validateSchedule(input.publishAt(), input.unpublishAt());

      String sql = """
          INSERT INTO products (
            sku, title, remark, currency, price, product_type, command_template,
            item_material, item_amount, effect_type, effect_seconds, effect_amplifier,
            publish_at, unpublish_at, active
          )
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON DUPLICATE KEY UPDATE
            title = VALUES(title),
            remark = VALUES(remark),
            currency = VALUES(currency),
            price = VALUES(price),
            product_type = VALUES(product_type),
            command_template = VALUES(command_template),
            item_material = VALUES(item_material),
            item_amount = VALUES(item_amount),
            effect_type = VALUES(effect_type),
            effect_seconds = VALUES(effect_seconds),
            effect_amplifier = VALUES(effect_amplifier),
            publish_at = VALUES(publish_at),
            unpublish_at = VALUES(unpublish_at),
            active = VALUES(active)
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, normalizedSku);
        statement.setString(2, input.title().trim());
        statement.setString(3, normalizedRemark);
        statement.setString(4, input.currency().name());
        statement.setLong(5, input.price());
        statement.setString(6, productType.name());
        statement.setString(7, normalizedCommand);
        statement.setString(8, normalizedItemMaterial);
        if (normalizedItemAmount == null) {
          statement.setObject(9, null);
        } else {
          statement.setInt(9, normalizedItemAmount);
        }
        statement.setString(10, normalizedEffectType);
        if (normalizedEffectSeconds == null) {
          statement.setObject(11, null);
        } else {
          statement.setInt(11, normalizedEffectSeconds);
        }
        if (normalizedEffectAmplifier == null) {
          statement.setObject(12, null);
        } else {
          statement.setInt(12, normalizedEffectAmplifier);
        }
        if (input.publishAt() == null) {
          statement.setObject(13, null);
        } else {
          statement.setTimestamp(13, java.sql.Timestamp.valueOf(input.publishAt()));
        }
        if (input.unpublishAt() == null) {
          statement.setObject(14, null);
        } else {
          statement.setTimestamp(14, java.sql.Timestamp.valueOf(input.unpublishAt()));
        }
        statement.setBoolean(15, input.active());
        statement.executeUpdate();
      }
      return readProductBySku(connection, normalizedSku);
    });
  }

  ProductView setProductActive(long productId, boolean active) {
    if (productId <= 0) {
      throw new ServiceException("invalid_product", "Product id must be positive");
    }
    return databaseManager.inTransaction(connection -> {
      String sql = "UPDATE products SET active = ? WHERE id = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setBoolean(1, active);
        statement.setLong(2, productId);
        int updated = statement.executeUpdate();
        if (updated == 0) {
          throw new ServiceException("product_missing", "Product is not available");
        }
      }
      return readProductById(connection, productId);
    });
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Lock clause is selected from a fixed boolean branch")
  ProductView readActiveProduct(Connection connection, long productId, boolean forUpdate)
      throws SQLException {
    String lockClause = forUpdate ? " FOR UPDATE" : "";
    String sql = """
        SELECT id, sku, title, remark, currency, price, product_type, command_template,
               item_material, item_amount, effect_type, effect_seconds, effect_amplifier,
               publish_at, unpublish_at, active
        FROM products
        WHERE id = ? AND active = TRUE
          AND (publish_at IS NULL OR publish_at <= NOW())
          AND (unpublish_at IS NULL OR unpublish_at > NOW())
        """ + lockClause;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("product_missing", "Product is not available");
        }
        return readProduct(resultSet);
      }
    }
  }

  private ProductView readProductById(Connection connection, long productId) throws SQLException {
    String sql = """
        SELECT id, sku, title, remark, currency, price, product_type, command_template,
               item_material, item_amount, effect_type, effect_seconds, effect_amplifier,
               publish_at, unpublish_at, active
        FROM products
        WHERE id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("product_missing", "Product is not available");
        }
        return readProduct(resultSet);
      }
    }
  }

  private ProductView readProductBySku(Connection connection, String sku) throws SQLException {
    String sql = """
        SELECT id, sku, title, remark, currency, price, product_type, command_template,
               item_material, item_amount, effect_type, effect_seconds, effect_amplifier,
               publish_at, unpublish_at, active
        FROM products
        WHERE sku = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, sku);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("product_missing", "Product is not available");
        }
        return readProduct(resultSet);
      }
    }
  }

  private void upsertSeed(Connection connection, PluginSettings.ProductSeed seed) throws SQLException {
    String sql = """
        INSERT INTO products (
          sku, title, currency, price, product_type, command_template, active
        )
        VALUES (?, ?, ?, ?, 'COMMAND', ?, TRUE)
        ON DUPLICATE KEY UPDATE
          title = VALUES(title),
          currency = VALUES(currency),
          price = VALUES(price),
          product_type = VALUES(product_type),
          command_template = VALUES(command_template),
          active = TRUE
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, normalizeSku(seed.sku()));
      statement.setString(2, seed.title());
      statement.setString(3, seed.currency().name());
      statement.setLong(4, seed.price());
      statement.setString(5, seed.commandTemplate());
      statement.executeUpdate();
    }
  }

  private ProductView readProduct(ResultSet resultSet) throws SQLException {
    String productTypeRaw = resultSet.getString("product_type");
    ProductType productType = ProductType.fromRaw(productTypeRaw);
    String itemMaterial = resultSet.getString("item_material");
    int itemAmountValue = resultSet.getInt("item_amount");
    Integer itemAmount = resultSet.wasNull() ? null : itemAmountValue;
    String effectType = resultSet.getString("effect_type");
    int effectSecondsValue = resultSet.getInt("effect_seconds");
    Integer effectSeconds = resultSet.wasNull() ? null : effectSecondsValue;
    int effectAmplifierValue = resultSet.getInt("effect_amplifier");
    Integer effectAmplifier = resultSet.wasNull() ? null : effectAmplifierValue;
    java.sql.Timestamp publishAtRaw = resultSet.getTimestamp("publish_at");
    java.sql.Timestamp unpublishAtRaw = resultSet.getTimestamp("unpublish_at");

    return new ProductView(
        resultSet.getLong("id"),
        resultSet.getString("sku"),
        resultSet.getString("title"),
        resultSet.getString("remark"),
        CurrencyType.valueOf(resultSet.getString("currency")),
        resultSet.getLong("price"),
        productType,
        resultSet.getString("command_template"),
        itemMaterial,
        itemAmount,
        effectType,
        effectSeconds,
        effectAmplifier,
        publishAtRaw == null ? null : publishAtRaw.toLocalDateTime(),
        unpublishAtRaw == null ? null : unpublishAtRaw.toLocalDateTime(),
        resultSet.getBoolean("active"));
  }

  private void validateAdminInput(AdminProductInput input) {
    if (input == null) {
      throw new ServiceException("bad_request", "Product input is required");
    }
    if (input.sku() == null || input.sku().isBlank()) {
      throw new ServiceException("invalid_product", "SKU is required");
    }
    if (input.title() == null || input.title().isBlank()) {
      throw new ServiceException("invalid_product", "Title is required");
    }
    if (input.currency() == null) {
      throw new ServiceException("invalid_product", "Currency is required");
    }
    if (input.price() <= 0L) {
      throw new ServiceException("invalid_product", "Price must be positive");
    }
    if (input.remark() != null && input.remark().length() > 1000) {
      throw new ServiceException("invalid_product", "Remark must be <= 1000 chars");
    }
    ProductType.fromRaw(input.productType());
  }

  private String normalizeSku(String rawSku) {
    String normalized = rawSku.trim().toLowerCase(Locale.ROOT);
    if (!normalized.matches("^[a-z0-9_\\-]{2,64}$")) {
      throw new ServiceException(
          "invalid_product",
          "SKU must be 2-64 chars (lowercase letters, numbers, _ or -)");
    }
    return normalized;
  }

  private String normalizeCommandTemplate(String commandTemplate, ProductType productType) {
    if (productType != ProductType.COMMAND) {
      return "";
    }
    if (commandTemplate == null || commandTemplate.isBlank()) {
      throw new ServiceException("invalid_product", "Command template is required for COMMAND type");
    }
    return commandTemplate.trim();
  }

  private String normalizeRemark(String remark) {
    if (remark == null) {
      return null;
    }
    String normalized = remark.trim();
    if (normalized.isEmpty()) {
      return null;
    }
    if (normalized.length() > 1000) {
      throw new ServiceException("invalid_product", "Remark must be <= 1000 chars");
    }
    return normalized;
  }

  private String normalizeItemMaterial(String itemMaterial, ProductType productType) {
    if (productType != ProductType.GIVE_ITEM && productType != ProductType.RECYCLE_ITEM) {
      return null;
    }
    if (itemMaterial == null || itemMaterial.isBlank()) {
      throw new ServiceException("invalid_product", "Item material is required");
    }
    String normalized = itemMaterial.trim();
    String key = normalized.toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
    Material material = Material.matchMaterial(key);
    if (material == null) {
      material = Material.matchMaterial(aliasMaterialKey(key));
    }
    if (material == null) {
      material = Material.matchMaterial(normalized.toLowerCase(Locale.ROOT));
    }
    if (material == null || material == Material.AIR) {
      throw new ServiceException("invalid_product", "Item material is invalid");
    }
    return material.name();
  }

  private Integer normalizeItemAmount(Integer itemAmount, ProductType productType) {
    if (itemAmount == null) {
      return null;
    }
    int normalized = itemAmount;
    if (normalized <= 0 || normalized > 100_000) {
      throw new ServiceException("invalid_product", "Max quantity must be between 1 and 100000");
    }
    return normalized;
  }

  private String aliasMaterialKey(String key) {
    if (key == null || key.isBlank()) {
      return key;
    }
    if (key.startsWith("BLOCK_OF_") && key.length() > "BLOCK_OF_".length()) {
      return key.substring("BLOCK_OF_".length()) + "_BLOCK";
    }
    return key;
  }

  private String normalizeEffectType(String effectType, ProductType productType) {
    if (productType != ProductType.POTION_EFFECT) {
      return null;
    }
    if (effectType == null || effectType.isBlank()) {
      throw new ServiceException("invalid_product", "Potion effect type is required");
    }
    String normalized = effectType.trim().toLowerCase(Locale.ROOT);
    if (!normalized.matches("^[a-z0-9_:\\-]{2,64}$")) {
      throw new ServiceException("invalid_product", "Potion effect type format is invalid");
    }
    if (!normalized.startsWith("minecraft:")) {
      return "minecraft:" + normalized;
    }
    return normalized;
  }

  private Integer normalizeEffectSeconds(Integer effectSeconds, ProductType productType) {
    if (productType != ProductType.POTION_EFFECT) {
      return null;
    }
    int normalized = effectSeconds == null ? 30 : effectSeconds;
    if (normalized <= 0 || normalized > 86_400) {
      throw new ServiceException("invalid_product", "Potion effect seconds must be between 1 and 86400");
    }
    return normalized;
  }

  private Integer normalizeEffectAmplifier(Integer effectAmplifier, ProductType productType) {
    if (productType != ProductType.POTION_EFFECT) {
      return null;
    }
    int normalized = effectAmplifier == null ? 0 : effectAmplifier;
    if (normalized < 0 || normalized > 255) {
      throw new ServiceException("invalid_product", "Potion effect amplifier must be between 0 and 255");
    }
    return normalized;
  }

  private void validateSchedule(
      java.time.LocalDateTime publishAt,
      java.time.LocalDateTime unpublishAt) {
    if (publishAt != null && unpublishAt != null && !unpublishAt.isAfter(publishAt)) {
      throw new ServiceException("invalid_product", "Unpublish time must be after publish time");
    }
  }

  private int normalizeLimit(int limit) {
    if (limit <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(limit, MAX_LIMIT);
  }

  enum ProductType {
    COMMAND,
    GIVE_ITEM,
    POTION_EFFECT,
    RECYCLE_ITEM,
    GROUP_BUY_VOUCHER;

    static ProductType fromRaw(String raw) {
      if (raw == null || raw.isBlank()) {
        return COMMAND;
      }
      try {
        return ProductType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException exception) {
        throw new ServiceException("invalid_product_type", "Product type is invalid");
      }
    }
  }

  record AdminProductInput(
      String sku,
      String title,
      String remark,
      CurrencyType currency,
      long price,
      String productType,
      String commandTemplate,
      String itemMaterial,
      Integer itemAmount,
      String effectType,
      Integer effectSeconds,
      Integer effectAmplifier,
      java.time.LocalDateTime publishAt,
      java.time.LocalDateTime unpublishAt,
      boolean active) {
  }

  record ProductView(
      long id,
      String sku,
      String title,
      String remark,
      CurrencyType currency,
      long price,
      ProductType productType,
      String commandTemplate,
      String itemMaterial,
      Integer itemAmount,
      String effectType,
      Integer effectSeconds,
      Integer effectAmplifier,
      java.time.LocalDateTime publishAt,
      java.time.LocalDateTime unpublishAt,
      boolean active) {
  }
}
