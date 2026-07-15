package com.webshopx;

import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;

class ProductService {
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 500;
  private static final int DYNAMIC_DECAY_STEP = 1;

  private final DatabaseManager databaseManager;
  private final SqlProvider sqlProvider;

  ProductService(DatabaseManager databaseManager) {
    this.databaseManager = databaseManager;
    this.sqlProvider = databaseManager.sqlProvider();
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

  List<ProductView> listActiveProductsForUser(long userId) {
    return databaseManager.withConnection(connection -> {
      List<ProductView> products = listProducts(connection, false, 300);
      Map<Long, Integer> usageByProduct = readUsageByProduct(connection, userId);
      List<ProductView> enriched = new ArrayList<>(products.size());
      for (ProductView product : products) {
        Integer perUserLimit = product.perUserLimit();
        if (perUserLimit == null) {
          enriched.add(product);
          continue;
        }
        int used = usageByProduct.getOrDefault(product.id(), 0);
        int remaining = Math.max(0, perUserLimit - used);
        enriched.add(product.withPersonalLimitRemaining(remaining));
      }
      return enriched;
    });
  }

  List<ProductView> listProducts(boolean includeInactive, int requestedLimit) {
    int limit = normalizeLimit(requestedLimit);
    return databaseManager.withConnection(connection -> listProducts(connection, includeInactive, limit));
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Active filter is selected from a fixed boolean branch and the limit remains bound")
  private List<ProductView> listProducts(Connection connection, boolean includeInactive, int limit)
      throws SQLException {
    LocalDateTime nowUtc = TimeSupport.utcNow();
    String activeFilter = includeInactive
        ? ""
        : "WHERE active = TRUE "
            + "AND (publish_at IS NULL OR publish_at <= ?) "
            + "AND (unpublish_at IS NULL OR unpublish_at > ?)";
    String sql = """
       SELECT id, sku, title, remark, currency, price, product_type, command_template,
         item_material, display_name_override, display_material, display_icon_path,
         item_amount, stock_remaining, per_user_limit,
         effect_type, effect_seconds, effect_amplifier,
         dynamic_pricing_enabled, dynamic_algorithm, dynamic_pricing_mode, dynamic_params_json,
         dynamic_base_price, dynamic_floor_price, dynamic_cap_price,
         dynamic_price_step, dynamic_demand_score,
               publish_at, unpublish_at, active
        FROM products
        """ + activeFilter + " ORDER BY id ASC LIMIT ?";
    List<ProductView> products = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int parameterIndex = 1;
      if (!includeInactive) {
        statement.setObject(parameterIndex++, nowUtc);
        statement.setObject(parameterIndex++, nowUtc);
      }
      statement.setInt(parameterIndex, limit);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          products.add(readProduct(resultSet));
        }
      }
    }
    return products;
  }

  ProductView upsertProduct(AdminProductInput input, boolean allowZeroPrice) {
    validateAdminInput(input, allowZeroPrice);
    return databaseManager.inTransaction(connection -> {
      String normalizedSku = normalizeSku(input.sku());
      ProductType productType = ProductType.fromRaw(input.productType());
      String normalizedRemark = normalizeRemark(input.remark());
      String normalizedCommand = normalizeCommandTemplate(input.commandTemplate(), productType);
      String normalizedItemMaterial = normalizeItemMaterial(input.itemMaterial(), productType);
      String normalizedDisplayNameOverride = normalizeDisplayNameOverride(input.displayNameOverride());
      String normalizedDisplayMaterial = normalizeDisplayMaterial(input.displayMaterial());
      String normalizedDisplayIconPath = normalizeDisplayIconPath(input.displayIconPath());
      Integer normalizedItemAmount = normalizeItemAmount(input.itemAmount(), productType);
      Integer normalizedPerUserLimit = normalizePerUserLimit(input.perUserLimit());
      String normalizedEffectType = normalizeEffectType(input.effectType(), productType);
      Integer normalizedEffectSeconds = normalizeEffectSeconds(input.effectSeconds(), productType);
      Integer normalizedEffectAmplifier = normalizeEffectAmplifier(
          input.effectAmplifier(),
          productType);
      validateSchedule(input.publishAt(), input.unpublishAt());

      ProductView existing = findProductBySku(connection, normalizedSku, true);
      Integer adjustedStockRemaining = resolveStockRemaining(normalizedItemAmount, existing);

      DynamicSettings dynamicSettings = normalizeDynamicSettings(input, productType, existing);
      long effectivePrice = input.price();
      if (dynamicSettings.enabled()) {
        long basePrice = dynamicSettings.basePrice() == null
            ? Math.max(1L, input.price())
            : dynamicSettings.basePrice();
        effectivePrice = computeDynamicPrice(
            dynamicSettings.algorithmType(),
            dynamicSettings.params(),
            basePrice,
            dynamicSettings.floorPrice(),
            dynamicSettings.capPrice(),
            dynamicSettings.priceStep(),
            dynamicSettings.demandScore());
      }

      if (existing == null) {
        String insertSql = """
            INSERT INTO products (
              sku, title, remark, currency, price, product_type, command_template,
              item_material, display_name_override, display_material, display_icon_path,
              item_amount, stock_remaining, per_user_limit,
              effect_type, effect_seconds, effect_amplifier,
              dynamic_pricing_enabled, dynamic_algorithm, dynamic_pricing_mode, dynamic_params_json,
              dynamic_base_price, dynamic_floor_price, dynamic_cap_price,
              dynamic_price_step, dynamic_demand_score,
              publish_at, unpublish_at, active
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
          statement.setString(1, normalizedSku);
          statement.setString(2, input.title().trim());
          statement.setString(3, normalizedRemark);
          statement.setString(4, input.currency().name());
          statement.setLong(5, effectivePrice);
          statement.setString(6, productType.name());
          statement.setString(7, normalizedCommand);
          statement.setString(8, normalizedItemMaterial);
          statement.setString(9, normalizedDisplayNameOverride);
          statement.setString(10, normalizedDisplayMaterial);
          statement.setString(11, normalizedDisplayIconPath);
          if (normalizedItemAmount == null) {
            statement.setObject(12, null);
            statement.setObject(13, null);
          } else {
            statement.setInt(12, normalizedItemAmount);
            statement.setInt(13, adjustedStockRemaining == null ? normalizedItemAmount : adjustedStockRemaining);
          }
          if (normalizedPerUserLimit == null) {
            statement.setObject(14, null);
          } else {
            statement.setInt(14, normalizedPerUserLimit);
          }
          statement.setString(15, normalizedEffectType);
          if (normalizedEffectSeconds == null) {
            statement.setObject(16, null);
          } else {
            statement.setInt(16, normalizedEffectSeconds);
          }
          if (normalizedEffectAmplifier == null) {
            statement.setObject(17, null);
          } else {
            statement.setInt(17, normalizedEffectAmplifier);
          }
          statement.setBoolean(18, dynamicSettings.enabled());
          statement.setString(19, dynamicSettings.algorithmType().name());
          statement.setString(20, dynamicSettings.pricingMode().name());
          statement.setString(21, MarketAlgorithmRegistry.toJson(dynamicSettings.params()));
          if (dynamicSettings.basePrice() == null) {
            statement.setObject(22, null);
          } else {
            statement.setLong(22, dynamicSettings.basePrice());
          }
          if (dynamicSettings.floorPrice() == null) {
            statement.setObject(23, null);
          } else {
            statement.setLong(23, dynamicSettings.floorPrice());
          }
          if (dynamicSettings.capPrice() == null) {
            statement.setObject(24, null);
          } else {
            statement.setLong(24, dynamicSettings.capPrice());
          }
          if (dynamicSettings.priceStep() == null) {
            statement.setObject(25, null);
          } else {
            statement.setLong(25, dynamicSettings.priceStep());
          }
          statement.setLong(26, dynamicSettings.demandScore());
          if (input.publishAt() == null) {
            statement.setObject(27, null);
          } else {
            statement.setObject(27, input.publishAt());
          }
          if (input.unpublishAt() == null) {
            statement.setObject(28, null);
          } else {
            statement.setObject(28, input.unpublishAt());
          }
          statement.setBoolean(29, input.active());
          statement.executeUpdate();
        }
      } else {
        String updateSql = """
            UPDATE products
            SET title = ?, remark = ?, currency = ?, price = ?, product_type = ?, command_template = ?,
                item_material = ?, display_name_override = ?, display_material = ?, display_icon_path = ?,
                item_amount = ?, stock_remaining = ?, per_user_limit = ?, effect_type = ?,
                effect_seconds = ?, effect_amplifier = ?,
                dynamic_pricing_enabled = ?, dynamic_algorithm = ?, dynamic_pricing_mode = ?, dynamic_params_json = ?,
                dynamic_base_price = ?, dynamic_floor_price = ?, dynamic_cap_price = ?,
                dynamic_price_step = ?, dynamic_demand_score = ?,
                publish_at = ?, unpublish_at = ?, active = ?
            WHERE id = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
          statement.setString(1, input.title().trim());
          statement.setString(2, normalizedRemark);
          statement.setString(3, input.currency().name());
          statement.setLong(4, effectivePrice);
          statement.setString(5, productType.name());
          statement.setString(6, normalizedCommand);
          statement.setString(7, normalizedItemMaterial);
          statement.setString(8, normalizedDisplayNameOverride);
          statement.setString(9, normalizedDisplayMaterial);
          statement.setString(10, normalizedDisplayIconPath);
          if (normalizedItemAmount == null) {
            statement.setObject(11, null);
            statement.setObject(12, null);
          } else {
            statement.setInt(11, normalizedItemAmount);
            statement.setInt(12, adjustedStockRemaining == null ? normalizedItemAmount : adjustedStockRemaining);
          }
          if (normalizedPerUserLimit == null) {
            statement.setObject(13, null);
          } else {
            statement.setInt(13, normalizedPerUserLimit);
          }
          statement.setString(14, normalizedEffectType);
          if (normalizedEffectSeconds == null) {
            statement.setObject(15, null);
          } else {
            statement.setInt(15, normalizedEffectSeconds);
          }
          if (normalizedEffectAmplifier == null) {
            statement.setObject(16, null);
          } else {
            statement.setInt(16, normalizedEffectAmplifier);
          }
          statement.setBoolean(17, dynamicSettings.enabled());
          statement.setString(18, dynamicSettings.algorithmType().name());
          statement.setString(19, dynamicSettings.pricingMode().name());
          statement.setString(20, MarketAlgorithmRegistry.toJson(dynamicSettings.params()));
          if (dynamicSettings.basePrice() == null) {
            statement.setObject(21, null);
          } else {
            statement.setLong(21, dynamicSettings.basePrice());
          }
          if (dynamicSettings.floorPrice() == null) {
            statement.setObject(22, null);
          } else {
            statement.setLong(22, dynamicSettings.floorPrice());
          }
          if (dynamicSettings.capPrice() == null) {
            statement.setObject(23, null);
          } else {
            statement.setLong(23, dynamicSettings.capPrice());
          }
          if (dynamicSettings.priceStep() == null) {
            statement.setObject(24, null);
          } else {
            statement.setLong(24, dynamicSettings.priceStep());
          }
          statement.setLong(25, dynamicSettings.demandScore());
          if (input.publishAt() == null) {
            statement.setObject(26, null);
          } else {
            statement.setObject(26, input.publishAt());
          }
          if (input.unpublishAt() == null) {
            statement.setObject(27, null);
          } else {
            statement.setObject(27, input.unpublishAt());
          }
          statement.setBoolean(28, input.active());
          statement.setLong(29, existing.id());
          statement.executeUpdate();
        }
      }
      return readProductBySku(connection, normalizedSku);
    });
  }

  long resolveOrderUnitPrice(ProductView product) {
    if (!supportsDynamicPricing(product)) {
      return Math.max(0L, product.price());
    }
    return computeDynamicPrice(
        MarketAlgorithmRegistry.DynamicAlgorithmType.fromRaw(product.dynamicAlgorithm()),
        MarketAlgorithmRegistry.parseParams(product.dynamicParamsJson()),
        resolveDynamicBasePrice(product),
        product.dynamicFloorPrice(),
        product.dynamicCapPrice(),
        product.dynamicPriceStep(),
        product.dynamicDemandScore());
  }

  ProductPriceQuote quoteOrderPrice(ProductView product, int quantity) {
    int normalizedQuantity = Math.max(1, quantity);
    if (!supportsDynamicPricing(product)) {
      long unitPrice = Math.max(0L, product.price());
      long totalAmount = Math.multiplyExact(unitPrice, normalizedQuantity);
      return new ProductPriceQuote(
          MarketAlgorithmRegistry.DynamicPricingMode.ORDER_FIXED,
          unitPrice,
          unitPrice,
          unitPrice,
          unitPrice,
          normalizedQuantity,
          totalAmount,
          product.dynamicDemandScore(),
          product.dynamicDemandScore());
    }
    MarketAlgorithmRegistry.DynamicAlgorithmType algorithmType =
        MarketAlgorithmRegistry.DynamicAlgorithmType.fromRaw(product.dynamicAlgorithm());
    MarketAlgorithmRegistry.DynamicPricingMode pricingMode =
        MarketAlgorithmRegistry.DynamicPricingMode.fromRaw(product.dynamicPricingMode());
    JsonObject params = MarketAlgorithmRegistry.parseParams(product.dynamicParamsJson());
    long currentDemand = product.dynamicDemandScore();
    MarketAlgorithmRegistry.DynamicPriceDirection direction = isRecycleProductType(product.productType())
        ? MarketAlgorithmRegistry.DynamicPriceDirection.RECYCLE
        : MarketAlgorithmRegistry.DynamicPriceDirection.PURCHASE;
    MarketAlgorithmRegistry.DynamicPriceQuote quote = MarketAlgorithmRegistry.computeDynamicPriceQuote(
        algorithmType,
        pricingMode,
        direction,
        resolveDynamicBasePrice(product),
        currentDemand,
        normalizedQuantity,
        product.dynamicPriceStep() == null ? 1L : Math.max(1L, product.dynamicPriceStep()),
        product.dynamicFloorPrice(),
        product.dynamicCapPrice(),
        params);
    return new ProductPriceQuote(
        quote.pricingMode(),
        quote.firstUnitPrice(),
        quote.lastUnitPrice(),
        quote.averageUnitPrice(),
        quote.nextUnitPrice(),
        normalizedQuantity,
        quote.totalAmount(),
        currentDemand,
        quote.nextDemandScore());
  }

  ProductView applyDynamicPriceEvent(
      Connection connection,
      ProductView product,
      int quantity,
      DynamicPriceEvent event) throws SQLException {
    if (quantity <= 0 || !supportsDynamicPricing(product)) {
      return product;
    }
    MarketAlgorithmRegistry.DynamicAlgorithmType algorithmType =
        MarketAlgorithmRegistry.DynamicAlgorithmType.fromRaw(product.dynamicAlgorithm());
    JsonObject params = MarketAlgorithmRegistry.parseParams(product.dynamicParamsJson());
    long currentDemand = product.dynamicDemandScore();
    long nextDemand = event == DynamicPriceEvent.PURCHASE
        ? MarketAlgorithmRegistry.computeDemandAfterPurchase(algorithmType, currentDemand, quantity, params)
        : MarketAlgorithmRegistry.computeDemandAfterRecycle(algorithmType, currentDemand, quantity, params);
    long nextPrice = computeDynamicPrice(
        algorithmType,
        params,
        resolveDynamicBasePrice(product),
        product.dynamicFloorPrice(),
        product.dynamicCapPrice(),
        product.dynamicPriceStep(),
        nextDemand);

    boolean backfillBasePrice = product.dynamicBasePrice() == null;
    long basePrice = resolveDynamicBasePrice(product);
    String sql = backfillBasePrice
        ? """
        UPDATE products
        SET dynamic_demand_score = ?, price = ?, dynamic_base_price = ?
        WHERE id = ?
        """
        : """
        UPDATE products
        SET dynamic_demand_score = ?, price = ?
        WHERE id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, nextDemand);
      statement.setLong(2, nextPrice);
      if (backfillBasePrice) {
        statement.setLong(3, basePrice);
        statement.setLong(4, product.id());
      } else {
        statement.setLong(3, product.id());
      }
      statement.executeUpdate();
    }
    return readProductById(connection, product.id());
  }

  void processDynamicPriceCycles() {
    databaseManager.inTransaction(connection -> {
      applyDynamicPriceDecayInTransaction(connection);
      return null;
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
    LocalDateTime nowUtc = TimeSupport.utcNow();
    String lockClause = forUpdate ? sqlProvider.forUpdateClause() : "";
    String sql = """
       SELECT id, sku, title, remark, currency, price, product_type, command_template,
         item_material, display_name_override, display_material, display_icon_path,
         item_amount, stock_remaining, per_user_limit,
         effect_type, effect_seconds, effect_amplifier,
         dynamic_pricing_enabled, dynamic_algorithm, dynamic_pricing_mode, dynamic_params_json,
         dynamic_base_price, dynamic_floor_price, dynamic_cap_price,
         dynamic_price_step, dynamic_demand_score,
               publish_at, unpublish_at, active
        FROM products
        WHERE id = ? AND active = TRUE
          AND (publish_at IS NULL OR publish_at <= ?)
          AND (unpublish_at IS NULL OR unpublish_at > ?)
        """ + lockClause;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, productId);
      statement.setObject(2, nowUtc);
      statement.setObject(3, nowUtc);
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
        item_material, display_name_override, display_material, display_icon_path,
        item_amount, stock_remaining, per_user_limit,
       effect_type, effect_seconds, effect_amplifier,
       dynamic_pricing_enabled, dynamic_algorithm, dynamic_pricing_mode, dynamic_params_json,
       dynamic_base_price, dynamic_floor_price, dynamic_cap_price,
       dynamic_price_step, dynamic_demand_score,
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
    ProductView view = findProductBySku(connection, sku, false);
    if (view == null) {
      throw new ServiceException("product_missing", "Product is not available");
    }
    return view;
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Lock clause is selected from a fixed boolean branch")
  private ProductView findProductBySku(Connection connection, String sku, boolean forUpdate)
      throws SQLException {
    String lockClause = forUpdate ? sqlProvider.forUpdateClause() : "";
    String sql = """
       SELECT id, sku, title, remark, currency, price, product_type, command_template,
         item_material, display_name_override, display_material, display_icon_path,
         item_amount, stock_remaining, per_user_limit,
         effect_type, effect_seconds, effect_amplifier,
         dynamic_pricing_enabled, dynamic_algorithm, dynamic_pricing_mode, dynamic_params_json,
         dynamic_base_price, dynamic_floor_price, dynamic_cap_price,
         dynamic_price_step, dynamic_demand_score,
               publish_at, unpublish_at, active
        FROM products
        WHERE sku = ?
        """ + lockClause;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, sku);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return readProduct(resultSet);
      }
    }
  }

  private void upsertSeed(Connection connection, PluginSettings.ProductSeed seed) throws SQLException {
    String sql = sqlProvider.upsertProductSeedSql();
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
    String displayNameOverride = resultSet.getString("display_name_override");
    String displayMaterial = resultSet.getString("display_material");
    String displayIconPath = resultSet.getString("display_icon_path");
    int itemAmountValue = resultSet.getInt("item_amount");
    Integer itemAmount = resultSet.wasNull() ? null : itemAmountValue;
    int stockRemainingValue = resultSet.getInt("stock_remaining");
    Integer stockRemaining = resultSet.wasNull() ? null : stockRemainingValue;
    int perUserLimitValue = resultSet.getInt("per_user_limit");
    Integer perUserLimit = resultSet.wasNull() ? null : perUserLimitValue;
    String effectType = resultSet.getString("effect_type");
    int effectSecondsValue = resultSet.getInt("effect_seconds");
    Integer effectSeconds = resultSet.wasNull() ? null : effectSecondsValue;
    int effectAmplifierValue = resultSet.getInt("effect_amplifier");
    Integer effectAmplifier = resultSet.wasNull() ? null : effectAmplifierValue;
    boolean dynamicPricingEnabled = resultSet.getBoolean("dynamic_pricing_enabled");
    String dynamicAlgorithm = resultSet.getString("dynamic_algorithm");
    String dynamicPricingMode = resultSet.getString("dynamic_pricing_mode");
    String dynamicParamsJson = resultSet.getString("dynamic_params_json");
    Long dynamicBasePrice = getNullableLong(resultSet, "dynamic_base_price");
    Long dynamicFloorPrice = getNullableLong(resultSet, "dynamic_floor_price");
    Long dynamicCapPrice = getNullableLong(resultSet, "dynamic_cap_price");
    Long dynamicPriceStep = getNullableLong(resultSet, "dynamic_price_step");
    long dynamicDemandScore = resultSet.getLong("dynamic_demand_score");
    LocalDateTime publishAtRaw = resultSet.getObject("publish_at", LocalDateTime.class);
    LocalDateTime unpublishAtRaw = resultSet.getObject("unpublish_at", LocalDateTime.class);

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
        displayNameOverride,
        displayMaterial,
        displayIconPath,
        itemAmount,
        stockRemaining,
        perUserLimit,
        effectType,
        effectSeconds,
        effectAmplifier,
          dynamicPricingEnabled,
          dynamicAlgorithm,
          dynamicPricingMode,
          dynamicParamsJson,
          dynamicBasePrice,
          dynamicFloorPrice,
          dynamicCapPrice,
          dynamicPriceStep,
          dynamicDemandScore,
        publishAtRaw,
        unpublishAtRaw,
        resultSet.getBoolean("active"),
        null);
  }

  private void validateAdminInput(AdminProductInput input, boolean allowZeroPrice) {
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
    if (input.price() < 0L) {
      throw new ServiceException("invalid_product", "Price cannot be negative");
    }
    if (!allowZeroPrice && input.price() == 0L) {
      throw new ServiceException("forbidden", "Admin permission required for zero-price products");
    }
    if (input.remark() != null && input.remark().length() > 1000) {
      throw new ServiceException("invalid_product", "Remark must be <= 1000 chars");
    }
    ProductType productType = ProductType.fromRaw(input.productType());
    validateDynamicInput(input, productType);
  }

  private void validateDynamicInput(AdminProductInput input, ProductType productType) {
    boolean dynamicSupported = supportsDynamicPricing(productType);
    boolean dynamicEnabled = input.dynamicPricingEnabled() != null && input.dynamicPricingEnabled();
    if (dynamicEnabled && !dynamicSupported) {
      throw new ServiceException(
          "invalid_dynamic_config",
          "Dynamic pricing is only supported for GIVE_ITEM/GIVE_CUSTOM_ITEM and RECYCLE_*");
    }
    normalizeOptionalPositive(input.dynamicBasePrice(), "invalid_dynamic_base");
    Long floorPrice = normalizeOptionalPositive(input.dynamicFloorPrice(), "invalid_dynamic_floor");
    Long capPrice = normalizeOptionalPositive(input.dynamicCapPrice(), "invalid_dynamic_cap");
    normalizeOptionalPositive(input.dynamicPriceStep(), "invalid_dynamic_step");
    if (floorPrice != null && capPrice != null && floorPrice > capPrice) {
      throw new ServiceException("invalid_dynamic_bounds", "Dynamic floor price must be <= cap price");
    }
    if (dynamicEnabled && input.dynamicBasePrice() == null && input.price() <= 0L) {
      throw new ServiceException("invalid_dynamic_base", "Dynamic base price must be positive");
    }
    if (input.dynamicParamsJson() != null && !input.dynamicParamsJson().isBlank()) {
      MarketAlgorithmRegistry.parseParams(input.dynamicParamsJson());
    }
    if (input.dynamicAlgorithm() != null && !input.dynamicAlgorithm().isBlank()) {
      MarketAlgorithmRegistry.DynamicAlgorithmType.fromRaw(input.dynamicAlgorithm());
    }
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
    if (!requiresCommandTemplate(productType)) {
      return "";
    }
    if (commandTemplate == null || commandTemplate.isBlank()) {
      throw new ServiceException("invalid_product", "Command template is required for COMMAND/GIVE_CUSTOM_ITEM/RECYCLE_COMMAND_ITEM/RECYCLE_CUSTOM_ITEM type");
    }
    String normalized = commandTemplate.trim();
    if (isAdvancedRecycleType(productType) && !usesQuantityPlaceholder(normalized)) {
      throw new ServiceException("invalid_product", "Recycle command template must include %amount% or {amount}");
    }
    return normalized;
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

  private String normalizeDisplayNameOverride(String raw) {
    if (raw == null) {
      return null;
    }
    String normalized = raw.trim();
    if (normalized.isEmpty()) {
      return null;
    }
    if (normalized.length() > 128) {
      return normalized.substring(0, 128);
    }
    return normalized;
  }

  private String normalizeDisplayMaterial(String raw) {
    if (raw == null) {
      return null;
    }
    String normalized = raw.trim()
        .toUpperCase(Locale.ROOT)
        .replace("MINECRAFT:", "")
        .replaceAll("[^A-Z0-9]+", "_")
        .replaceAll("^_+|_+$", "");
    if (normalized.isEmpty()) {
      return null;
    }
    if (normalized.length() > 64) {
      return normalized.substring(0, 64);
    }
    return normalized;
  }

  private String normalizeDisplayIconPath(String raw) {
    if (raw == null) {
      return null;
    }
    String normalized = raw.trim();
    if (normalized.isEmpty()) {
      return null;
    }
    if (normalized.length() > 255) {
      return normalized.substring(0, 255);
    }
    return normalized;
  }

  private String normalizeItemMaterial(String itemMaterial, ProductType productType) {
    if (productType != ProductType.GIVE_ITEM
        && productType != ProductType.GIVE_CUSTOM_ITEM
        && productType != ProductType.RECYCLE_ITEM
        && !isAdvancedRecycleType(productType)) {
      return null;
    }
    if (itemMaterial == null || itemMaterial.isBlank()) {
      throw new ServiceException("invalid_product", "Item material is required");
    }
    String normalized = itemMaterial.trim();
    if (isAdvancedRecycleType(productType) || productType == ProductType.GIVE_CUSTOM_ITEM) {
      return normalizeAdvancedRecycleMaterialKey(normalized);
    }
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
      throw new ServiceException("invalid_product", "Stock must be between 1 and 100000");
    }
    return normalized;
  }

  private Integer normalizePerUserLimit(Integer perUserLimit) {
    if (perUserLimit == null) {
      return null;
    }
    int normalized = perUserLimit;
    if (normalized <= 0 || normalized > 100_000) {
      throw new ServiceException("invalid_product", "Per-user limit must be between 1 and 100000");
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

  private Integer resolveStockRemaining(Integer newLimit, ProductView existing) {
    if (newLimit == null) {
      return null;
    }
    int sold = 0;
    if (existing != null && existing.itemAmount() != null && existing.stockRemaining() != null) {
      sold = Math.max(0, existing.itemAmount() - existing.stockRemaining());
    }
    int remaining = Math.max(0, newLimit - sold);
    return remaining;
  }

  private DynamicSettings normalizeDynamicSettings(
      AdminProductInput input,
      ProductType productType,
      ProductView existing) {
    boolean enabled = input.dynamicPricingEnabled() != null
        ? input.dynamicPricingEnabled() && supportsDynamicPricing(productType)
        : existing != null && existing.dynamicPricingEnabled() && supportsDynamicPricing(productType);
    if (!enabled) {
      return new DynamicSettings(
          false,
          MarketAlgorithmRegistry.DynamicAlgorithmType.LINEAR_DEMAND_V1,
          MarketAlgorithmRegistry.DynamicPricingMode.ORDER_FIXED,
          new JsonObject(),
          null,
          null,
          null,
          null,
          0L);
    }

    MarketAlgorithmRegistry.DynamicAlgorithmType algorithmType = MarketAlgorithmRegistry.DynamicAlgorithmType
        .fromRaw(input.dynamicAlgorithm());
    MarketAlgorithmRegistry.DynamicPricingMode pricingMode = input.dynamicPricingMode() == null
        ? (existing == null
            ? (isRecycleProductType(productType)
                ? MarketAlgorithmRegistry.DynamicPricingMode.PER_UNIT_MARGINAL
                : MarketAlgorithmRegistry.DynamicPricingMode.ORDER_FIXED)
            : MarketAlgorithmRegistry.DynamicPricingMode.fromRaw(existing.dynamicPricingMode()))
        : MarketAlgorithmRegistry.DynamicPricingMode.fromRaw(input.dynamicPricingMode());
    JsonObject params = MarketAlgorithmRegistry.parseParams(input.dynamicParamsJson());
    Long basePrice = normalizeOptionalPositive(input.dynamicBasePrice(), "invalid_dynamic_base");
    Long floorPrice = normalizeOptionalPositive(input.dynamicFloorPrice(), "invalid_dynamic_floor");
    Long capPrice = normalizeOptionalPositive(input.dynamicCapPrice(), "invalid_dynamic_cap");
    Long priceStep = normalizeOptionalPositive(input.dynamicPriceStep(), "invalid_dynamic_step");
    if (floorPrice != null && capPrice != null && floorPrice > capPrice) {
      throw new ServiceException("invalid_dynamic_bounds", "Dynamic floor price must be <= cap price");
    }
    if (basePrice == null) {
      if (existing != null && existing.dynamicBasePrice() != null) {
        basePrice = Math.max(1L, existing.dynamicBasePrice());
      } else if (input.price() > 0L) {
        basePrice = input.price();
      } else if (existing != null && existing.price() > 0L) {
        basePrice = existing.price();
      } else {
        basePrice = 1L;
      }
    }

    long demandScore = existing == null ? 0L : existing.dynamicDemandScore();
    return new DynamicSettings(
        true,
        algorithmType,
        pricingMode,
        params,
        basePrice,
        floorPrice,
        capPrice,
        priceStep,
        demandScore);
  }

  private boolean supportsDynamicPricing(ProductType productType) {
    return true;
  }

  private boolean supportsDynamicPricing(ProductView product) {
    return product.dynamicPricingEnabled() && supportsDynamicPricing(product.productType());
  }

  private long resolveDynamicBasePrice(ProductView product) {
    if (product.dynamicBasePrice() != null) {
      return Math.max(1L, product.dynamicBasePrice());
    }
    return Math.max(1L, product.price());
  }

  private long computeDynamicPrice(
      MarketAlgorithmRegistry.DynamicAlgorithmType algorithmType,
      JsonObject params,
      Long basePrice,
      Long floorPrice,
      Long capPrice,
      Long priceStep,
      long demandScore) {
    long resolvedBase = basePrice == null ? 1L : Math.max(1L, basePrice);
    long resolvedStep = priceStep == null ? 1L : Math.max(1L, priceStep);
    return MarketAlgorithmRegistry.computeDynamicPrice(
        algorithmType,
        resolvedBase,
        Math.max(0L, demandScore),
        resolvedStep,
        floorPrice,
        capPrice,
        params);
  }

  private Long normalizeOptionalPositive(Long value, String code) {
    if (value == null) {
      return null;
    }
    if (value <= 0L) {
      throw new ServiceException(code, "Dynamic pricing numeric fields must be positive");
    }
    return value;
  }

  private int applyDynamicPriceDecayInTransaction(Connection connection) throws SQLException {
    String selectSql =
        """
        SELECT id, price, dynamic_algorithm, dynamic_params_json,
               dynamic_base_price, dynamic_floor_price, dynamic_cap_price, dynamic_price_step,
               dynamic_demand_score
        FROM products
        WHERE active = TRUE
          AND dynamic_pricing_enabled = TRUE
          AND product_type IN ('GIVE_ITEM', 'GIVE_CUSTOM_ITEM', 'RECYCLE_ITEM', 'RECYCLE_COMMAND_ITEM', 'RECYCLE_CUSTOM_ITEM')
          AND dynamic_demand_score > 0
        %s
        """
            .formatted(sqlProvider.forUpdateClause());
    List<DynamicDecayTarget> targets = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(selectSql);
         ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        targets.add(new DynamicDecayTarget(
            resultSet.getLong("id"),
            resultSet.getLong("price"),
            resultSet.getString("dynamic_algorithm"),
            resultSet.getString("dynamic_params_json"),
            getNullableLong(resultSet, "dynamic_base_price"),
            getNullableLong(resultSet, "dynamic_floor_price"),
            getNullableLong(resultSet, "dynamic_cap_price"),
            getNullableLong(resultSet, "dynamic_price_step"),
            resultSet.getLong("dynamic_demand_score")));
      }
    }
    if (targets.isEmpty()) {
      return 0;
    }

    String updateSql = """
        UPDATE products
        SET dynamic_demand_score = ?, price = ?, dynamic_base_price = ?
        WHERE id = ?
        """;
    int updated = 0;
    try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
      for (DynamicDecayTarget target : targets) {
        MarketAlgorithmRegistry.DynamicAlgorithmType algorithmType =
            MarketAlgorithmRegistry.DynamicAlgorithmType.fromRaw(target.dynamicAlgorithm());
        JsonObject params = MarketAlgorithmRegistry.parseParams(target.dynamicParamsJson());
        long nextDemand = MarketAlgorithmRegistry.computeDemandAfterDecay(
            target.dynamicDemandScore(),
            DYNAMIC_DECAY_STEP);
        long basePrice = target.dynamicBasePrice() == null
            ? Math.max(1L, target.currentPrice())
            : Math.max(1L, target.dynamicBasePrice());
        long nextPrice = computeDynamicPrice(
            algorithmType,
            params,
            basePrice,
            target.dynamicFloorPrice(),
            target.dynamicCapPrice(),
            target.dynamicPriceStep(),
            nextDemand);
        statement.setLong(1, nextDemand);
        statement.setLong(2, nextPrice);
        statement.setLong(3, basePrice);
        statement.setLong(4, target.productId());
        statement.addBatch();
      }
      int[] counts = statement.executeBatch();
      for (int count : counts) {
        if (count > 0) {
          updated += count;
        }
      }
    }
    return updated;
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

  ProductView readProductView(long productId) {
    if (productId <= 0) {
      throw new ServiceException("invalid_product", "Product id must be positive");
    }
    return databaseManager.withConnection(connection -> readProductById(connection, productId));
  }

  ProductPriceQuote quoteProduct(long productId, int quantity) {
    if (productId <= 0) {
      throw new ServiceException("invalid_product", "Product id must be positive");
    }
    return databaseManager.withConnection(connection -> {
      ProductView product = readActiveProduct(connection, productId, false);
      return quoteOrderPrice(product, quantity);
    });
  }

  List<ProductPriceTrendPoint> listPriceTrend(long productId, int requestedLimit) {
    if (productId <= 0) {
      throw new ServiceException("invalid_product", "Product id must be positive");
    }
    int limit = Math.max(1, Math.min(requestedLimit, 80));
    return databaseManager.withConnection(connection -> {
      readProductById(connection, productId);
      String sql = """
          SELECT oi.id, oi.unit_price, oi.quantity, o.created_at
          FROM order_items oi
          JOIN orders o ON o.id = oi.order_id
          WHERE oi.product_id = ?
            AND UPPER(o.status) <> 'REFUNDED'
          ORDER BY oi.id DESC
          LIMIT ?
          """;
      List<ProductPriceTrendPoint> points = new ArrayList<>();
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, productId);
        statement.setInt(2, limit);
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            points.add(new ProductPriceTrendPoint(
                resultSet.getLong("id"),
                resultSet.getLong("unit_price"),
                resultSet.getInt("quantity"),
                resultSet.getTimestamp("created_at").toLocalDateTime()));
          }
        }
      }
      java.util.Collections.reverse(points);
      return points;
    });
  }

  ProductView updateDisplayIconPath(long productId, String displayIconPath) {
    if (productId <= 0L) {
      throw new ServiceException("invalid_product", "Product id must be positive");
    }
    String normalizedPath = normalizeDisplayIconPath(displayIconPath);
    return databaseManager.inTransaction(connection -> {
      ProductView existing = readProductById(connection, productId);
      String sql = "UPDATE products SET display_icon_path = ? WHERE id = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, normalizedPath);
        statement.setLong(2, productId);
        statement.executeUpdate();
      }
      return readProductById(connection, existing.id());
    });
  }

  private Map<Long, Integer> readUsageByProduct(Connection connection, long userId) throws SQLException {
    String sql = """
        SELECT product_id, used_count
        FROM product_user_usage
        WHERE user_id = ?
        """;
    Map<Long, Integer> usage = new HashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          usage.put(resultSet.getLong("product_id"), Math.max(0, resultSet.getInt("used_count")));
        }
      }
    }
    return usage;
  }

  private boolean isRecycleProductType(ProductType productType) {
    return productType == ProductType.RECYCLE_ITEM
        || productType == ProductType.RECYCLE_COMMAND_ITEM
        || productType == ProductType.RECYCLE_CUSTOM_ITEM;
  }

  enum ProductType {
    COMMAND,
    GIVE_ITEM,
    GIVE_CUSTOM_ITEM,
    POTION_EFFECT,
    RECYCLE_ITEM,
    RECYCLE_COMMAND_ITEM,
    RECYCLE_CUSTOM_ITEM,
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
      String displayNameOverride,
      String displayMaterial,
      String displayIconPath,
      Integer itemAmount,
      Integer perUserLimit,
      String effectType,
      Integer effectSeconds,
      Integer effectAmplifier,
      Boolean dynamicPricingEnabled,
      String dynamicAlgorithm,
      String dynamicPricingMode,
      String dynamicParamsJson,
      Long dynamicBasePrice,
      Long dynamicFloorPrice,
      Long dynamicCapPrice,
      Long dynamicPriceStep,
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
      String displayNameOverride,
      String displayMaterial,
      String displayIconPath,
      Integer itemAmount,
      Integer stockRemaining,
      Integer perUserLimit,
      String effectType,
      Integer effectSeconds,
      Integer effectAmplifier,
      boolean dynamicPricingEnabled,
      String dynamicAlgorithm,
      String dynamicPricingMode,
      String dynamicParamsJson,
      Long dynamicBasePrice,
      Long dynamicFloorPrice,
      Long dynamicCapPrice,
      Long dynamicPriceStep,
      long dynamicDemandScore,
      java.time.LocalDateTime publishAt,
      java.time.LocalDateTime unpublishAt,
      boolean active,
      Integer personalLimitRemaining) {
    ProductView withPersonalLimitRemaining(Integer remaining) {
      return new ProductView(
          id,
          sku,
          title,
          remark,
          currency,
          price,
          productType,
          commandTemplate,
          itemMaterial,
          displayNameOverride,
          displayMaterial,
          displayIconPath,
          itemAmount,
          stockRemaining,
          perUserLimit,
          effectType,
          effectSeconds,
          effectAmplifier,
          dynamicPricingEnabled,
          dynamicAlgorithm,
          dynamicPricingMode,
          dynamicParamsJson,
          dynamicBasePrice,
          dynamicFloorPrice,
          dynamicCapPrice,
          dynamicPriceStep,
          dynamicDemandScore,
          publishAt,
          unpublishAt,
          active,
          remaining);
    }
  }

  record ProductPriceQuote(
      MarketAlgorithmRegistry.DynamicPricingMode pricingMode,
      long firstUnitPrice,
      long lastUnitPrice,
      long averageUnitPrice,
      long nextUnitPrice,
      int quantity,
      long totalAmount,
      long currentDemandScore,
      long nextDemandScore) {
  }

  record ProductPriceTrendPoint(
      long orderItemId,
      long price,
      int quantity,
      LocalDateTime createdAt) {
  }

  private Long getNullableLong(ResultSet resultSet, String column) throws SQLException {
    Object value = resultSet.getObject(column);
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    throw new SQLException("Column '" + column + "' is not numeric: " + value.getClass().getName());
  }

  enum DynamicPriceEvent {
    PURCHASE,
    RECYCLE
  }

  private boolean requiresCommandTemplate(ProductType productType) {
    return productType == ProductType.COMMAND
        || productType == ProductType.GIVE_CUSTOM_ITEM
        || isAdvancedRecycleType(productType);
  }

  private boolean isAdvancedRecycleType(ProductType productType) {
    return productType == ProductType.RECYCLE_COMMAND_ITEM
        || productType == ProductType.RECYCLE_CUSTOM_ITEM;
  }

  private boolean usesQuantityPlaceholder(String template) {
    if (template == null || template.isBlank()) {
      return false;
    }
    String normalized = template.toLowerCase(Locale.ROOT);
    return normalized.contains("%amount%")
        || normalized.contains("{amount}")
        || normalized.contains("%quantity%")
        || normalized.contains("{quantity}");
  }

  private String normalizeAdvancedRecycleMaterialKey(String raw) {
    String text = raw.trim();
    if (text.length() > 128) {
      throw new ServiceException("invalid_product", "Item material is invalid");
    }
    if (text.contains(":")) {
      String namespaced = text.toLowerCase(Locale.ROOT);
      if (!namespaced.matches("^[a-z0-9._-]+:[a-z0-9._\\-/]+$")) {
        throw new ServiceException("invalid_product", "Item material is invalid");
      }
      return namespaced;
    }
    String normalized = text.toUpperCase(Locale.ROOT)
        .replace("MINECRAFT:", "")
        .replaceAll("[^A-Z0-9_]+", "_")
        .replaceAll("^_+|_+$", "");
    if (normalized.isBlank() || normalized.length() > 64) {
      throw new ServiceException("invalid_product", "Item material is invalid");
    }
    return normalized;
  }

  private record DynamicSettings(
      boolean enabled,
      MarketAlgorithmRegistry.DynamicAlgorithmType algorithmType,
      MarketAlgorithmRegistry.DynamicPricingMode pricingMode,
      JsonObject params,
      Long basePrice,
      Long floorPrice,
      Long capPrice,
      Long priceStep,
      long demandScore) {
  }

  private record DynamicDecayTarget(
      long productId,
      long currentPrice,
      String dynamicAlgorithm,
      String dynamicParamsJson,
      Long dynamicBasePrice,
      Long dynamicFloorPrice,
      Long dynamicCapPrice,
      Long dynamicPriceStep,
      long dynamicDemandScore) {
  }
}

