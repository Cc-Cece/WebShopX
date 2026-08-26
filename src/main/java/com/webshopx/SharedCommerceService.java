package com.webshopx;

import com.google.gson.JsonObject;
import com.webshopx.core.ItemEnvelopeBinaryCodec;
import com.webshopx.core.ItemEnvelopeService;
import com.webshopx.payment.api.PaymentConfigDescriptor;
import com.webshopx.payment.api.PaymentConfigSnapshot;
import com.webshopx.payment.api.PaymentConfigUpdateRequest;
import com.webshopx.payment.api.PaymentConfigUpdateResult;
import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.ItemEnvelope;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Loader-neutral official-shop, market escrow, and recharge transaction graph. */
public final class SharedCommerceService {
  private final DatabaseManager database;
  private final WalletService wallets;
  private final ItemEnvelopeBinaryCodec envelopes = new ItemEnvelopeBinaryCodec();

  public SharedSupplyService supplyService(
      com.webshopx.platform.SupplyInventoryGateway gateway, String serverId) {
    return new SharedSupplyService(database, this, gateway, serverId);
  }
  private final Map<String, PaymentProvider> paymentProviders = new ConcurrentHashMap<>();

  public SharedCommerceService(DatabaseManager database, WalletService wallets) {
    this.database = Objects.requireNonNull(database, "database");
    this.wallets = Objects.requireNonNull(wallets, "wallets");
  }

  public Product createProduct(ProductInput input) {
    validateProduct(input);
    return database.inTransaction(
        connection -> {
          String sql =
              "INSERT INTO products (sku,title,remark,currency,price,product_type,"
                  + "command_template,item_material,item_amount,stock_remaining,per_user_limit,active) "
                  + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
          try (PreparedStatement statement =
              connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, input.sku().trim().toUpperCase(Locale.ROOT));
            statement.setString(2, input.title().trim());
            statement.setString(3, input.remark());
            statement.setString(4, input.currency().name());
            statement.setLong(5, input.price());
            statement.setString(6, input.kind().name());
            statement.setString(
                7, input.commandTemplate() == null ? "" : input.commandTemplate().trim());
            statement.setString(8, input.registryId());
            if (input.stock() == null) {
              statement.setObject(9, null);
              statement.setObject(10, null);
            } else {
              statement.setInt(9, input.stock());
              statement.setInt(10, input.stock());
            }
            setNullableInt(statement, 11, input.perUserLimit());
            statement.setBoolean(12, input.active());
            statement.executeUpdate();
            return readProduct(connection, generatedId(statement));
          }
        });
  }

  public Product upsertProduct(ProductInput input) {
    validateProduct(input);
    return database.inTransaction(
        connection -> {
          String sku = input.sku().trim().toUpperCase(Locale.ROOT);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE products SET title=?,remark=?,currency=?,price=?,product_type=?,"
                      + "command_template=?,item_material=?,item_amount=?,stock_remaining=?,"
                      + "per_user_limit=?,active=?,updated_at=CURRENT_TIMESTAMP WHERE sku=?")) {
            statement.setString(1, input.title().trim());
            statement.setString(2, input.remark());
            statement.setString(3, input.currency().name());
            statement.setLong(4, input.price());
            statement.setString(5, input.kind().name());
            statement.setString(
                6, input.commandTemplate() == null ? "" : input.commandTemplate().trim());
            statement.setString(7, input.registryId());
            if (input.stock() == null) {
              statement.setObject(8, null);
              statement.setObject(9, null);
            } else {
              statement.setInt(8, input.stock());
              statement.setInt(9, input.stock());
            }
            setNullableInt(statement, 10, input.perUserLimit());
            statement.setBoolean(11, input.active());
            statement.setString(12, sku);
            if (statement.executeUpdate() == 1) return readProductBySku(connection, sku);
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO products (sku,title,remark,currency,price,product_type,"
                      + "command_template,item_material,item_amount,stock_remaining,per_user_limit,active)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                  Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, sku);
            statement.setString(2, input.title().trim());
            statement.setString(3, input.remark());
            statement.setString(4, input.currency().name());
            statement.setLong(5, input.price());
            statement.setString(6, input.kind().name());
            statement.setString(
                7, input.commandTemplate() == null ? "" : input.commandTemplate().trim());
            statement.setString(8, input.registryId());
            if (input.stock() == null) {
              statement.setObject(9, null);
              statement.setObject(10, null);
            } else {
              statement.setInt(9, input.stock());
              statement.setInt(10, input.stock());
            }
            setNullableInt(statement, 11, input.perUserLimit());
            statement.setBoolean(12, input.active());
            statement.executeUpdate();
            return readProduct(connection, generatedId(statement));
          }
        });
  }

  public Product setProductActive(long productId, boolean active) {
    return database.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE products SET active=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")) {
            statement.setBoolean(1, active);
            statement.setLong(2, productId);
            if (statement.executeUpdate() != 1) {
              throw new ServiceException("product_not_found", "Product was not found");
            }
          }
          return readProduct(connection, productId);
        });
  }

  public int resetProductUserLimitUsage(long productId) {
    if (productId <= 0) {
      throw new ServiceException("invalid_product", "Product id must be positive");
    }
    return database.inTransaction(
        connection -> {
          readProduct(connection, productId);
          try (PreparedStatement statement =
              connection.prepareStatement("DELETE FROM product_user_usage WHERE product_id=?")) {
            statement.setLong(1, productId);
            return statement.executeUpdate();
          }
        });
  }

  public AssetPathUpdate updateProductIcon(long productId, String assetPath) {
    requireManagedAssetPath(assetPath, "/uploads/product-icons/");
    return database.inTransaction(
        connection -> {
          readProduct(connection, productId);
          String previous;
          try (PreparedStatement statement = connection.prepareStatement(
              "SELECT display_icon_path FROM products WHERE id=?")) {
            statement.setLong(1, productId);
            try (ResultSet result = statement.executeQuery()) {
              result.next();
              previous = result.getString(1);
            }
          }
          try (PreparedStatement statement = connection.prepareStatement(
              "UPDATE products SET display_icon_path=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")) {
            statement.setString(1, assetPath);
            statement.setLong(2, productId);
            statement.executeUpdate();
          }
          return new AssetPathUpdate(productId, previous, assetPath);
        });
  }

  public Product createSnapshotProduct(
      ProductInput input, ItemEnvelope template, long createdBy) {
    if (input == null || input.kind() != ProductKind.SNAPSHOT_ITEM) {
      throw new ServiceException("invalid_product", "Snapshot product type is required");
    }
    validateProduct(input);
    ItemEnvelope normalized = normalizedTemplate(template);
    return database.inTransaction(
        connection -> {
          long snapshotId = findOrCreateOfficialSnapshot(connection, normalized);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO products (sku,title,remark,currency,price,product_type,"
                      + "command_template,item_material,item_amount,stock_remaining,per_user_limit,"
                      + "snapshot_id,inventory_mode,active) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,"
                      + "'TEMPLATE',?)",
                  Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, input.sku().trim().toUpperCase(Locale.ROOT));
            statement.setString(2, input.title().trim());
            statement.setString(3, input.remark());
            statement.setString(4, input.currency().name());
            statement.setLong(5, input.price());
            statement.setString(6, ProductKind.SNAPSHOT_ITEM.name());
            statement.setString(7, "");
            statement.setString(8, normalized.registryId());
            if (input.stock() == null) {
              statement.setObject(9, null);
              statement.setObject(10, null);
            } else {
              statement.setInt(9, input.stock());
              statement.setInt(10, input.stock());
            }
            setNullableInt(statement, 11, input.perUserLimit());
            statement.setLong(12, snapshotId);
            statement.setBoolean(13, input.active());
            statement.executeUpdate();
            long productId = generatedId(statement);
            insertSnapshotVersion(
                connection, productId, snapshotId, snapshotStorageHash(normalized), createdBy);
            return readProduct(connection, productId);
          }
        });
  }

  public Product replaceSnapshot(long productId, ItemEnvelope template, long createdBy) {
    ItemEnvelope normalized = normalizedTemplate(template);
    return database.inTransaction(
        connection -> {
          lockProductForSnapshot(connection, productId);
          Product product = readProduct(connection, productId);
          if (product.kind() != ProductKind.SNAPSHOT_ITEM) {
            throw new ServiceException(
                "invalid_product_type", "Product does not use item snapshots");
          }
          long snapshotId = findOrCreateOfficialSnapshot(connection, normalized);
          try (PreparedStatement statement = connection.prepareStatement(
              "UPDATE products SET snapshot_id=?,item_material=?,updated_at=CURRENT_TIMESTAMP"
                  + " WHERE id=?")) {
            statement.setLong(1, snapshotId);
            statement.setString(2, normalized.registryId());
            statement.setLong(3, productId);
            statement.executeUpdate();
          }
          insertSnapshotVersion(
              connection, productId, snapshotId, snapshotStorageHash(normalized), createdBy);
          return readProduct(connection, productId);
        });
  }

  public List<SnapshotVersion> snapshotVersions(long productId) {
    return database.withConnection(
        connection -> {
          Product product = readProduct(connection, productId);
          try (PreparedStatement statement = connection.prepareStatement(
              "SELECT v.id,v.snapshot_id,v.version,v.item_hash,v.created_by,v.active_from,"
                  + "s.item_material,s.item_meta_json,p.snapshot_id FROM product_item_snapshots v"
                  + " JOIN official_item_snapshots s ON s.id=v.snapshot_id"
                  + " JOIN products p ON p.id=v.product_id WHERE v.product_id=?"
                  + " ORDER BY v.version DESC")) {
            statement.setLong(1, product.id());
            try (ResultSet result = statement.executeQuery()) {
              List<SnapshotVersion> versions = new ArrayList<>();
              while (result.next()) {
                Object creator = result.getObject(5);
                versions.add(new SnapshotVersion(
                    result.getLong(1),
                    result.getLong(2),
                    result.getInt(3),
                    result.getString(4),
                    creator == null ? null : ((Number) creator).longValue(),
                    instant(result, 6),
                    result.getString(7),
                    result.getString(8),
                    result.getLong(2) == result.getLong(9)));
              }
              return List.copyOf(versions);
            }
          }
        });
  }

  public Product rollbackSnapshot(long productId, int version, long createdBy) {
    if (version < 1) throw new ServiceException("invalid_snapshot", "Snapshot version is invalid");
    return database.inTransaction(
        connection -> {
          lockProductForSnapshot(connection, productId);
          Product product = readProduct(connection, productId);
          if (product.kind() != ProductKind.SNAPSHOT_ITEM) {
            throw new ServiceException(
                "invalid_product_type", "Product does not use item snapshots");
          }
          long snapshotId;
          String itemHash;
          String material;
          try (PreparedStatement statement = connection.prepareStatement(
              "SELECT v.snapshot_id,v.item_hash,s.item_material FROM product_item_snapshots v"
                  + " JOIN official_item_snapshots s ON s.id=v.snapshot_id"
                  + " WHERE v.product_id=? AND v.version=?")) {
            statement.setLong(1, productId);
            statement.setInt(2, version);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) {
                throw new ServiceException(
                    "snapshot_version_missing", "Snapshot version was not found");
              }
              snapshotId = result.getLong(1);
              itemHash = result.getString(2);
              material = result.getString(3);
            }
          }
          try (PreparedStatement statement = connection.prepareStatement(
              "UPDATE products SET snapshot_id=?,item_material=?,updated_at=CURRENT_TIMESTAMP"
                  + " WHERE id=?")) {
            statement.setLong(1, snapshotId);
            statement.setString(2, material);
            statement.setLong(3, productId);
            statement.executeUpdate();
          }
          insertSnapshotVersion(connection, productId, snapshotId, itemHash, createdBy);
          return readProduct(connection, productId);
        });
  }

  public List<Product> products(boolean includeInactive) {
    return database.withConnection(
        connection -> {
          String sql =
              "SELECT id,sku,title,remark,currency,price,product_type,command_template,"
                  + "item_material,stock_remaining,per_user_limit,active FROM products"
                  + (includeInactive ? "" : " WHERE active=TRUE")
                  + " ORDER BY id";
          try (PreparedStatement statement = connection.prepareStatement(sql);
              ResultSet result = statement.executeQuery()) {
            List<Product> values = new ArrayList<>();
            while (result.next()) values.add(product(result));
            return List.copyOf(values);
          }
        });
  }

  public ProductQuote quoteProduct(long productId, int quantity) {
    if (quantity < 1) throw new ServiceException("invalid_quantity", "Quantity is invalid");
    Product product = product(productId);
    if (!product.active()) throw new ServiceException("product_inactive", "Product is inactive");
    if (product.stockRemaining() != null && product.stockRemaining() < quantity) {
      throw new ServiceException("insufficient_stock", "Product stock is insufficient");
    }
    long total = Math.multiplyExact(product.price(), quantity);
    return new ProductQuote(
        product.id(),
        product.price(),
        product.price(),
        product.price(),
        product.price(),
        quantity,
        total,
        0L,
        0L);
  }

  public List<OfficialRecycleMatch> matchingRecycleProducts(
      long userId, ItemEnvelope item, int quantity) {
    if (item == null || quantity < 1) return List.of();
    return database.withConnection(connection -> {
      List<OfficialRecycleMatch> matches = new ArrayList<>();
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT id,sku,title,currency,price,per_user_limit FROM products "
              + "WHERE active=TRUE AND product_type='RECYCLE_ITEM' AND LOWER(item_material)=? "
              + "ORDER BY id")) {
        statement.setString(1, item.registryId().toLowerCase(Locale.ROOT));
        try (ResultSet result = statement.executeQuery()) {
          while (result.next()) {
            Integer limit = nullableInt(result, "per_user_limit");
            int used = productUsage(connection, result.getLong("id"), userId);
            int remaining = limit == null ? Integer.MAX_VALUE : Math.max(0, limit - used);
            if (remaining < quantity) continue;
            long unitPrice = result.getLong("price");
            matches.add(new OfficialRecycleMatch(
                result.getLong("id"), result.getString("sku"), result.getString("title"),
                CurrencyType.valueOf(result.getString("currency")), unitPrice, quantity,
                Math.multiplyExact(unitPrice, quantity), remaining));
          }
        }
      }
      return List.copyOf(matches);
    });
  }

  OfficialRecycleResult completeOfficialRecycle(
      Connection connection,
      long userId,
      UUID playerId,
      long productId,
      ItemEnvelope removed,
      int quantity,
      String idempotencyKey) throws SQLException {
    Product product = readProduct(connection, productId);
    if (!product.active() || product.kind() != ProductKind.RECYCLE_ITEM) {
      throw new ServiceException("recycle_unavailable", "Recycle product is unavailable");
    }
    if (removed == null || !removed.registryId().equalsIgnoreCase(product.registryId())
        || removed.count() != quantity) {
      throw new ServiceException("recycle_item_not_match", "Selected item does not match recycle product");
    }
    reserveProductUserLimit(connection, product, userId, quantity);
    long total = Math.multiplyExact(product.price(), quantity);
    String orderNo = "REC-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    wallets.applyDelta(
        connection, userId, product.currency(), total, "OFFICIAL_RECYCLE", orderNo, false);
    long orderId;
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO orders (order_no,user_id,mc_uuid,currency,total_amount,status,idempotency_key) "
            + "VALUES (?,?,?,?,?,'DELIVERED',?)", Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, orderNo);
      statement.setLong(2, userId);
      statement.setString(3, playerId.toString());
      statement.setString(4, product.currency().name());
      statement.setLong(5, total);
      statement.setString(6, idempotencyKey);
      statement.executeUpdate();
      orderId = generatedId(statement);
    }
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO order_items (order_id,product_id,quantity,unit_price) VALUES (?,?,?,?)")) {
      statement.setLong(1, orderId);
      statement.setLong(2, product.id());
      statement.setInt(3, quantity);
      statement.setLong(4, product.price());
      statement.executeUpdate();
    }
    return new OfficialRecycleResult(
        orderId, orderNo, product.id(), product.currency(), product.price(), quantity, total);
  }

  private static int productUsage(Connection connection, long productId, long userId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT used_count FROM product_user_usage WHERE product_id=? AND user_id=?")) {
      statement.setLong(1, productId);
      statement.setLong(2, userId);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? Math.max(0, result.getInt(1)) : 0;
      }
    }
  }

  public List<ProductPricePoint> productPriceTrend(long productId, int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, 80));
    return database.withConnection(
        connection -> {
          readProduct(connection, productId);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT oi.id,oi.unit_price,oi.quantity,o.created_at FROM order_items oi"
                      + " JOIN orders o ON o.id=oi.order_id WHERE oi.product_id=?"
                      + " AND UPPER(o.status)<>'REFUNDED' ORDER BY oi.id DESC LIMIT ?")) {
            statement.setLong(1, productId);
            statement.setInt(2, boundedLimit);
            try (ResultSet result = statement.executeQuery()) {
              List<ProductPricePoint> values = new ArrayList<>();
              while (result.next()) {
                values.add(
                    new ProductPricePoint(
                        result.getLong(1), result.getLong(2), result.getInt(3), instant(result, 4)));
              }
              java.util.Collections.reverse(values);
              return List.copyOf(values);
            }
          }
        });
  }

  public Purchase purchase(PurchaseRequest request) {
    if (request.quantity() < 1 || request.quantity() > 100_000) {
      throw new ServiceException("invalid_quantity", "Quantity is invalid");
    }
    requireKey(request.idempotencyKey());
    return database.inTransaction(
        connection -> {
          Purchase existing = findPurchase(connection, request.userId(), request.idempotencyKey());
          if (existing != null) return existing;
          Product product = readProduct(connection, request.productId());
          if (!product.active())
            throw new ServiceException("product_inactive", "Product is inactive");
          if (isRecycleProduct(product.kind())) {
            throw new ServiceException(
                "recycle_requires_inventory", "Recycle products require an inventory item");
          }
          if (product.kind() == ProductKind.GROUP_BUY_VOUCHER && request.quantity() != 1) {
            throw new ServiceException(
                "invalid_quantity", "Group-buy vouchers must be purchased one at a time");
          }
          if (product.stockRemaining() != null && product.stockRemaining() < request.quantity()) {
            throw new ServiceException("insufficient_stock", "Product stock is insufficient");
          }
          reserveProductUserLimit(connection, product, request.userId(), request.quantity());
          long total;
          try {
            total = Math.multiplyExact(product.price(), request.quantity());
          } catch (ArithmeticException overflow) {
            throw new ServiceException("invalid_amount", "Order total overflow");
          }
          String orderNo =
              "MOD-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
          String orderStatus =
              product.kind() == ProductKind.GROUP_BUY_VOUCHER ? "DELIVERED" : "PAID";
          wallets.applyDelta(
              connection,
              request.userId(),
              product.currency(),
              -total,
              "ORDER_PURCHASE",
              orderNo,
              true);
          long orderId;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO orders (order_no,user_id,mc_uuid,currency,total_amount,status,"
                      + "idempotency_key,target_server_id,claim_token) VALUES (?,?,?,?,?,?,?,?,?)",
                  Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, orderNo);
            statement.setLong(2, request.userId());
            statement.setString(3, request.playerId().toString());
            statement.setString(4, product.currency().name());
            statement.setLong(5, total);
            statement.setString(6, orderStatus);
            statement.setString(7, request.idempotencyKey());
            statement.setString(8, request.targetServerId());
            statement.setString(9, UUID.randomUUID().toString());
            statement.executeUpdate();
            orderId = generatedId(statement);
          }
          long itemId;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO order_items (order_id,product_id,quantity,unit_price) VALUES"
                      + " (?,?,?,?)",
                  Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, orderId);
            statement.setLong(2, product.id());
            statement.setInt(3, request.quantity());
            statement.setLong(4, product.price());
            statement.executeUpdate();
            itemId = generatedId(statement);
          }
          if (product.kind() == ProductKind.GROUP_BUY_VOUCHER) {
            insertGroupBuyVoucher(connection, orderId, request.userId(), product.id());
          } else {
            insertProductDelivery(connection, request, product, orderId, itemId);
          }
          if (product.stockRemaining() != null) {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "UPDATE products SET"
                        + " stock_remaining=stock_remaining-?,updated_at=CURRENT_TIMESTAMP WHERE"
                        + " id=? AND stock_remaining>=?")) {
              statement.setInt(1, request.quantity());
              statement.setLong(2, product.id());
              statement.setInt(3, request.quantity());
              if (statement.executeUpdate() != 1) {
                throw new ServiceException("stock_conflict", "Product stock changed concurrently");
              }
            }
          }
          return new Purchase(
              orderId,
              orderNo,
              request.userId(),
              product.id(),
              request.quantity(),
              product.currency(),
              total,
              orderStatus,
              request.idempotencyKey());
        });
  }

  public List<Delivery> pendingDeliveries(UUID playerId, String serverId) {
    return database.withConnection(
        connection -> {
          String sql =
              "SELECT d.id,d.order_id,d.delivery_kind,d.command_text,d.payload_json,d.quantity,"
                  + "d.delivered_quantity,d.status FROM delivery_queue d "
                  + "WHERE d.mc_uuid=? AND d.status IN ('PENDING','RETRY') "
                  + "AND (d.target_server_id IS NULL OR d.target_server_id=?) ORDER BY d.id";
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, serverId);
            try (ResultSet result = statement.executeQuery()) {
              List<Delivery> values = new ArrayList<>();
              while (result.next())
                values.add(
                    new Delivery(
                        result.getLong(1),
                        result.getLong(2),
                        ProductKind.valueOf(result.getString(3)),
                        result.getString(4),
                        result.getString(5),
                        result.getInt(6),
                        result.getInt(7),
                        result.getString(8)));
              return List.copyOf(values);
            }
          }
        });
  }

  public List<OrderView> orders(long userId, int limit, Long cursor) {
    int normalizedLimit = Math.max(1, Math.min(limit, 100));
    return database.withConnection(
        connection -> {
          String sql =
              "SELECT o.id,o.order_no,o.status,o.currency,o.total_amount,o.mc_uuid,o.created_at,"
                  + "o.delivered_at,o.refunded_at,o.refund_deadline,o.refunded_amount,"
                  + "o.refunded_quantity,o.claim_token,oi.quantity,oi.unit_price,p.sku,p.title,"
                  + "p.remark,p.product_type,p.item_material,gv.code,gv.status,gv.consumed_at"
                  + " FROM orders o JOIN order_items oi"
                  + " ON oi.order_id=o.id JOIN products p ON p.id=oi.product_id"
                  + " LEFT JOIN group_buy_vouchers gv ON gv.order_id=o.id"
                  + " WHERE o.user_id=?"
                  + (cursor == null ? "" : " AND o.id<?")
                  + " ORDER BY o.id DESC LIMIT ?";
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            statement.setLong(parameter++, userId);
            if (cursor != null) statement.setLong(parameter++, cursor);
            statement.setInt(parameter, normalizedLimit);
            List<OrderView> values = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) {
                long orderId = result.getLong(1);
                int delivered = deliveredQuantity(connection, orderId);
                int quantity = result.getInt(14);
                String status = result.getString(3);
                values.add(
                    new OrderView(
                        orderId,
                        result.getString(2),
                        status,
                        result.getString(4),
                        result.getLong(5),
                        result.getString(6),
                        instant(result, 7),
                        instant(result, 8),
                        instant(result, 9),
                        instant(result, 10),
                        result.getLong(11),
                        result.getInt(12),
                        result.getString(13),
                        quantity,
                        result.getLong(15),
                        result.getString(16),
                        result.getString(17),
                        result.getString(18),
                        result.getString(19),
                        result.getString(20),
                        delivered,
                        ("PAID".equals(status)
                                || ("DELIVERED".equals(status)
                                    && "ISSUED".equalsIgnoreCase(result.getString(22))))
                            && delivered == 0
                            && !"CONSUMED".equalsIgnoreCase(result.getString(22)),
                        Math.max(0, quantity - result.getInt(12)),
                        result.getString(21),
                        result.getString(22),
                        instant(result, 23)));
              }
            }
            return List.copyOf(values);
          }
        });
  }

  public List<AdminOrderView> adminOrders(
      int requestedLimit,
      Long cursor,
      String status,
      Long userId,
      String orderNo,
      String keyword,
      String currency,
      String productType) {
    int limit = Math.max(1, Math.min(requestedLimit, 200));
    return database.withConnection(
        connection -> {
          StringBuilder sql =
              new StringBuilder(
                  "SELECT o.id,o.order_no,o.status,o.currency,o.total_amount,o.mc_uuid,"
                      + "o.created_at,o.delivered_at,o.refunded_at,o.refund_deadline,"
                      + "o.refunded_amount,o.refunded_quantity,o.claim_token,oi.quantity,"
                      + "oi.unit_price,p.sku,p.title,p.remark,p.product_type,p.item_material,"
                      + "u.id,u.username,u.bound_uuid,gv.code,gv.status,gv.consumed_at"
                      + " FROM orders o JOIN order_items oi"
                      + " ON oi.order_id=o.id JOIN products p ON p.id=oi.product_id"
                      + " JOIN web_users u ON u.id=o.user_id"
                      + " LEFT JOIN group_buy_vouchers gv ON gv.order_id=o.id WHERE 1=1");
          List<Object> parameters = new ArrayList<>();
          if (cursor != null) {
            sql.append(" AND o.id<?");
            parameters.add(cursor);
          }
          if (status != null && !status.isBlank()) {
            sql.append(" AND UPPER(o.status)=?");
            parameters.add(status.trim().toUpperCase(Locale.ROOT));
          }
          if (userId != null) {
            sql.append(" AND o.user_id=?");
            parameters.add(userId);
          }
          if (orderNo != null && !orderNo.isBlank()) {
            sql.append(" AND UPPER(o.order_no)=?");
            parameters.add(orderNo.trim().toUpperCase(Locale.ROOT));
          }
          if (keyword != null && !keyword.isBlank()) {
            sql.append(
                " AND (LOWER(o.order_no) LIKE ? OR LOWER(u.username) LIKE ?"
                    + " OR LOWER(p.sku) LIKE ? OR LOWER(p.title) LIKE ?)");
            String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
            parameters.add(pattern);
            parameters.add(pattern);
            parameters.add(pattern);
            parameters.add(pattern);
          }
          if (currency != null && !currency.isBlank()) {
            sql.append(" AND UPPER(o.currency)=?");
            parameters.add(currency.trim().toUpperCase(Locale.ROOT));
          }
          if (productType != null && !productType.isBlank()) {
            sql.append(" AND UPPER(p.product_type)=?");
            parameters.add(productType.trim().toUpperCase(Locale.ROOT));
          }
          sql.append(" ORDER BY o.id DESC LIMIT ?");
          parameters.add(limit);
          try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
              statement.setObject(index + 1, parameters.get(index));
            }
            try (ResultSet result = statement.executeQuery()) {
              List<AdminOrderView> values = new ArrayList<>();
              while (result.next()) {
                long id = result.getLong(1);
                int delivered = deliveredQuantity(connection, id);
                int quantity = result.getInt(14);
                String orderStatus = result.getString(3);
                OrderView order =
                    new OrderView(
                        id,
                        result.getString(2),
                        orderStatus,
                        result.getString(4),
                        result.getLong(5),
                        result.getString(6),
                        instant(result, 7),
                        instant(result, 8),
                        instant(result, 9),
                        instant(result, 10),
                        result.getLong(11),
                        result.getInt(12),
                        result.getString(13),
                        quantity,
                        result.getLong(15),
                        result.getString(16),
                        result.getString(17),
                        result.getString(18),
                        result.getString(19),
                        result.getString(20),
                        delivered,
                        ("PAID".equals(orderStatus)
                                || ("DELIVERED".equals(orderStatus)
                                    && "ISSUED".equalsIgnoreCase(result.getString(25))))
                            && delivered == 0
                            && !"CONSUMED".equalsIgnoreCase(result.getString(25)),
                        Math.max(0, quantity - result.getInt(12)),
                        result.getString(24),
                        result.getString(25),
                        instant(result, 26));
                String uuid = result.getString(23);
                values.add(
                    new AdminOrderView(
                        order,
                        result.getLong(21),
                        result.getString(22),
                        uuid == null || uuid.isBlank() ? null : UUID.fromString(uuid)));
              }
              return List.copyOf(values);
            }
          }
        });
  }

  public DeliveryStatus deliveryStatus(long userId, String orderNo) {
    return database.withConnection(
        connection -> {
          long orderId;
          String status;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id,status FROM orders WHERE user_id=? AND order_no=?")) {
            statement.setLong(1, userId);
            statement.setString(2, orderNo);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) throw new ServiceException("order_not_found", "Order not found");
              orderId = result.getLong(1);
              status = result.getString(2);
            }
          }
          List<DeliveryTask> tasks = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id,status,retry_count,last_error,next_retry_at,delivered_at,claimed_at,"
                      + "created_at,quantity,delivered_quantity,delivery_kind,target_server_id"
                      + " FROM delivery_queue WHERE order_id=? ORDER BY id")) {
            statement.setLong(1, orderId);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) {
                tasks.add(
                    new DeliveryTask(
                        result.getLong(1),
                        result.getString(2),
                        result.getInt(3),
                        result.getString(4),
                        instant(result, 5),
                        instant(result, 6),
                        instant(result, 7),
                        instant(result, 8),
                        result.getInt(9),
                        result.getInt(10),
                        result.getString(11),
                        result.getString(12)));
              }
            }
          }
          return new DeliveryStatus(orderNo, status, List.copyOf(tasks));
        });
  }

  public List<MailboxEntry> mailboxItems(long userId, int requestedLimit, Long cursor) {
    int limit = Math.max(1, Math.min(requestedLimit, 100));
    return database.withConnection(
        connection -> {
          String sql =
              "SELECT id,source_type,source_ref,item_blob,quantity,delivered_quantity,reason,"
                  + "status,last_error,claimed_at,created_at FROM mailbox_items WHERE user_id=?"
                  + " AND status IN ('PENDING','PARTIAL','PROCESSING')"
                  + (cursor == null ? "" : " AND id<?")
                  + " ORDER BY id DESC LIMIT ?";
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            statement.setLong(parameter++, userId);
            if (cursor != null) statement.setLong(parameter++, cursor);
            statement.setInt(parameter, limit);
            try (ResultSet result = statement.executeQuery()) {
              List<MailboxEntry> values = new ArrayList<>();
              while (result.next()) {
                ItemEnvelope item = envelopes.decode(result.getBytes(4));
                int quantity = result.getInt(5);
                int delivered = result.getInt(6);
                values.add(
                    new MailboxEntry(
                        "MAILBOX:" + result.getLong(1),
                        "ITEM",
                        result.getString(2),
                        result.getString(3),
                        item.registryId(),
                        item.registryId(),
                        quantity,
                        delivered,
                        0,
                        result.getString(8),
                        instant(result, 11),
                        !"PROCESSING".equals(result.getString(8)),
                        false,
                        "PRODUCT_NOT_REFUNDABLE",
                        result.getString(7),
                        result.getString(9),
                        instant(result, 10),
                        item.payloadHash()));
              }
              String marketSql =
                  "SELECT id,listing_id,trade_id,delivery_type,item_blob,quantity,"
                      + "delivered_quantity,status,last_error,claimed_at,created_at"
                      + " FROM market_item_deliveries WHERE target_user_id=?"
                      + " AND status IN ('PENDING','RETRY','PARTIAL','PROCESSING')"
                      + (cursor == null ? "" : " AND id<?")
                      + " ORDER BY id DESC LIMIT ?";
              try (PreparedStatement market = connection.prepareStatement(marketSql)) {
                int marketParameter = 1;
                market.setLong(marketParameter++, userId);
                if (cursor != null) market.setLong(marketParameter++, cursor);
                market.setInt(marketParameter, limit);
                try (ResultSet delivery = market.executeQuery()) {
                  while (delivery.next()) {
                    ItemEnvelope item = envelopes.decode(delivery.getBytes(5));
                    values.add(
                        new MailboxEntry(
                            "MARKET:" + delivery.getLong(1),
                            "ITEM",
                            "MARKET_" + delivery.getString(4),
                            delivery.getObject(3) == null
                                ? "listing:" + delivery.getLong(2)
                                : "trade:" + delivery.getLong(3),
                            item.registryId(),
                            item.registryId(),
                            delivery.getInt(6),
                            delivery.getInt(7),
                            0,
                            delivery.getString(8),
                            instant(delivery, 11),
                            !"PROCESSING".equals(delivery.getString(8)),
                            false,
                            "MARKET_DELIVERY_NOT_REFUNDABLE",
                            delivery.getString(4),
                            delivery.getString(9),
                            instant(delivery, 10),
                            item.payloadHash()));
                  }
                }
              }
              return values.stream()
                  .sorted(Comparator.comparing(MailboxEntry::createdAt).reversed())
                  .limit(limit)
                  .toList();
            }
          }
        });
  }

  public int mailboxCount(long userId) {
    return database.withConnection(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT COUNT(*) FROM mailbox_items WHERE user_id=?"
                      + " AND status IN ('PENDING','PARTIAL','PROCESSING')")) {
            statement.setLong(1, userId);
            int standalone;
            try (ResultSet result = statement.executeQuery()) {
              standalone = result.next() ? result.getInt(1) : 0;
            }
            try (PreparedStatement market =
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM market_item_deliveries WHERE target_user_id=?"
                        + " AND status IN ('PENDING','RETRY','PARTIAL','PROCESSING')")) {
              market.setLong(1, userId);
              try (ResultSet result = market.executeQuery()) {
                return standalone + (result.next() ? result.getInt(1) : 0);
              }
            }
          }
        });
  }

  public GroupBuyVoucher groupBuyVoucher(long userId, long orderId) {
    return database.withConnection(
        connection -> readGroupBuyVoucher(connection, userId, orderId, null, false));
  }

  public GroupBuyVoucher consumeGroupBuyVoucher(long adminUserId, String rawCode) {
    String code = normalizeGroupBuyVoucherCode(rawCode);
    return database.inTransaction(
        connection -> {
          GroupBuyVoucher voucher = readGroupBuyVoucher(connection, null, null, code, true);
          if (voucher == null) {
            throw new ServiceException("voucher_missing", "Group-buy voucher not found");
          }
          if ("REFUNDED".equals(voucher.status())) {
            throw new ServiceException(
                "voucher_refunded", "Group-buy voucher has been refunded");
          }
          if (!"ISSUED".equals(voucher.status())) {
            throw new ServiceException(
                "voucher_unavailable", "Group-buy voucher is already consumed");
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE group_buy_vouchers SET status='CONSUMED',"
                      + "consumed_by_admin_id=?,consumed_at=CURRENT_TIMESTAMP"
                      + " WHERE id=? AND status='ISSUED'")) {
            statement.setLong(1, adminUserId);
            statement.setLong(2, voucher.id());
            if (statement.executeUpdate() != 1) {
              throw new ServiceException(
                  "voucher_unavailable", "Group-buy voucher is already consumed");
            }
          }
          return readGroupBuyVoucher(connection, null, null, code, true);
        });
  }

  public RefundResult refundOrder(long userId, String orderNo) {
    String idempotencyKey = "order-refund:" + orderNo;
    RefundResult refund;
    try {
      refund =
          database.inTransaction(
              connection -> {
                RefundResult prior = priorRefund(connection, userId, orderNo, idempotencyKey);
                if (prior != null) return prior;
                long orderId;
                CurrencyType currency;
                long total;
                int quantity;
                String status;
                try (PreparedStatement statement =
                    connection.prepareStatement(
                        "SELECT o.id,o.currency,o.total_amount,o.status,SUM(oi.quantity) FROM"
                            + " orders o JOIN order_items oi ON oi.order_id=o.id WHERE o.user_id=?"
                            + " AND o.order_no=? GROUP BY"
                            + " o.id,o.currency,o.total_amount,o.status")) {
                  statement.setLong(1, userId);
                  statement.setString(2, orderNo);
                  try (ResultSet result = statement.executeQuery()) {
                    if (!result.next())
                      throw new ServiceException("order_not_found", "Order not found");
                    orderId = result.getLong(1);
                    currency = CurrencyType.valueOf(result.getString(2));
                    total = result.getLong(3);
                    status = result.getString(4);
                    quantity = result.getInt(5);
                  }
                }
                String voucherStatus = groupBuyVoucherStatus(connection, orderId);
                if ("CONSUMED".equals(voucherStatus)) {
                  throw new ServiceException(
                      "voucher_consumed", "Group-buy voucher has already been consumed");
                }
                boolean refundableStatus =
                    "PAID".equals(status)
                        || ("DELIVERED".equals(status) && "ISSUED".equals(voucherStatus));
                if (!refundableStatus || deliveredQuantity(connection, orderId) > 0) {
                  throw new ServiceException("order_not_refundable", "Order is not refundable");
                }
                if (hasProcessingDelivery(connection, orderId)) {
                  throw new ServiceException(
                      "delivery_outcome_unknown",
                      "Delivery is in progress; reconciliation is required");
                }
                insertRefundRequest(connection, userId, orderNo, idempotencyKey);
                try (PreparedStatement statement =
                    connection.prepareStatement(
                        "UPDATE orders SET"
                            + " status='REFUNDED',refunded_quantity=?,refunded_amount=?,refunded_at=CURRENT_TIMESTAMP"
                            + " WHERE id=? AND user_id=? AND status IN ('PAID','DELIVERED')")) {
                  statement.setInt(1, quantity);
                  statement.setLong(2, total);
                  statement.setLong(3, orderId);
                  statement.setLong(4, userId);
                  if (statement.executeUpdate() != 1) {
                    throw new ServiceException("order_conflict", "Order state changed");
                  }
                }
                cancelDeliveries(connection, orderId);
                markGroupBuyVoucherRefunded(connection, orderId);
                restoreProductStock(connection, orderId);
                wallets.applyDelta(
                    connection, userId, currency, total, "ORDER_REFUND", orderNo, false);
                try (PreparedStatement statement =
                    connection.prepareStatement(
                        "UPDATE refund_requests SET"
                            + " status='SUCCESS',refund_amount=?,refund_quantity=?,completed_at=CURRENT_TIMESTAMP"
                            + " WHERE user_id=? AND idempotency_key=?")) {
                  statement.setLong(1, total);
                  statement.setInt(2, quantity);
                  statement.setLong(3, userId);
                  statement.setString(4, idempotencyKey);
                  statement.executeUpdate();
                }
                return new RefundResult(orderNo, total, quantity, null);
              });
    } catch (RuntimeException failure) {
      RefundResult recovered =
          database.withConnection(
              connection -> priorRefund(connection, userId, orderNo, idempotencyKey));
      if (recovered == null) throw failure;
      refund = recovered;
    }
    return new RefundResult(
        refund.orderNo(),
        refund.refundAmount(),
        refund.refundQuantity(),
        wallets.getBalance(userId));
  }

  public void discardOrder(long userId, String orderNo) {
    database.inTransaction(
        connection -> {
          long orderId;
          String orderStatus;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id,status FROM orders WHERE user_id=? AND order_no=?")) {
            statement.setLong(1, userId);
            statement.setString(2, orderNo);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next())
                throw new ServiceException("order_not_discardable", "Order is not discardable");
              orderId = result.getLong(1);
              orderStatus = result.getString(2);
            }
          }
          if ("CANCELLED".equals(orderStatus)) return null;
          if (!"PAID".equals(orderStatus)) {
            throw new ServiceException("order_not_discardable", "Order is not discardable");
          }
          if (groupBuyVoucherStatus(connection, orderId) != null) {
            throw new ServiceException(
                "order_not_discardable", "Group-buy voucher orders cannot be discarded");
          }
          if (deliveredQuantity(connection, orderId) > 0
              || hasProcessingDelivery(connection, orderId)) {
            throw new ServiceException("order_not_discardable", "Order is not discardable");
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE orders SET status='CANCELLED' WHERE id=? AND status='PAID'")) {
            statement.setLong(1, orderId);
            if (statement.executeUpdate() != 1) {
              throw new ServiceException("order_conflict", "Order state changed");
            }
          }
          cancelDeliveries(connection, orderId);
          return null;
        });
  }

  public boolean claimDelivery(long deliveryId, String serverId) {
    if (serverId == null || serverId.isBlank()) {
      throw new IllegalArgumentException("serverId must not be blank");
    }
    return database.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE delivery_queue SET status='PROCESSING',claimed_at=CURRENT_TIMESTAMP,"
                      + "last_error=NULL WHERE id=? AND status IN ('PENDING','RETRY') "
                      + "AND (target_server_id IS NULL OR target_server_id=?)")) {
            statement.setLong(1, deliveryId);
            statement.setString(2, serverId);
            return statement.executeUpdate() == 1;
          }
        });
  }

  public void markDelivered(long deliveryId, int deliveredQuantity) {
    if (deliveredQuantity < 1)
      throw new ServiceException("invalid_quantity", "Quantity is invalid");
    database.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE delivery_queue SET delivered_quantity=delivered_quantity+?,status=CASE"
                      + " WHEN delivered_quantity+?>=quantity THEN 'DELIVERED' ELSE 'PENDING'"
                      + " END,delivered_at=CASE WHEN delivered_quantity+?>=quantity THEN"
                      + " CURRENT_TIMESTAMP ELSE delivered_at END WHERE id=? AND"
                      + " status='PROCESSING' AND delivered_quantity+?<=quantity")) {
            statement.setInt(1, deliveredQuantity);
            statement.setInt(2, deliveredQuantity);
            statement.setInt(3, deliveredQuantity);
            statement.setLong(4, deliveryId);
            statement.setInt(5, deliveredQuantity);
            if (statement.executeUpdate() != 1) {
              throw new ServiceException("delivery_conflict", "Delivery state changed");
            }
          }
          return null;
        });
  }

  public void markDeliveryRetry(long deliveryId, String error) {
    transitionDelivery(deliveryId, "RETRY", error);
  }

  public void markDeliveryUnknown(long deliveryId, String error) {
    transitionDelivery(deliveryId, "UNKNOWN", error);
  }

  private void transitionDelivery(long deliveryId, String status, String error) {
    String safeError = error == null ? "unspecified" : error;
    if (safeError.length() > 500) safeError = safeError.substring(0, 500);
    String finalError = safeError;
    database.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE delivery_queue SET status=?,last_error=?,retry_count=retry_count+1,"
                      + "next_retry_at=CURRENT_TIMESTAMP WHERE id=? AND status='PROCESSING'")) {
            statement.setString(1, status);
            statement.setString(2, finalError);
            statement.setLong(3, deliveryId);
            if (statement.executeUpdate() != 1) {
              throw new ServiceException("delivery_conflict", "Delivery state changed");
            }
          }
          return null;
        });
  }

  public Listing createListing(ListingRequest request) {
    if (request.price() < 1
        || request.quantity() < 1
        || request.quantity() > request.item().count()) {
      throw new ServiceException("invalid_listing", "Listing price or quantity is invalid");
    }
    return database.inTransaction(connection -> createListing(connection, request));
  }

  Listing createListing(Connection connection, ListingRequest request) throws SQLException {
    if (request.price() < 1
        || request.quantity() < 1
        || request.quantity() > request.item().count()) {
      throw new ServiceException("invalid_listing", "Listing price or quantity is invalid");
    }
    byte[] blob = envelopes.encode(request.item());
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO market_listings (seller_user_id,seller_uuid,currency,price,quantity,"
                + "quantity_total,item_material,raw_item_blob,item_meta_json,remark,item_hash,escrow_total,escrow_remaining,status,market_side,trade_mode)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE','SELL','DIRECT')",
            Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, request.sellerUserId());
      statement.setString(2, request.sellerId().toString());
      statement.setString(3, request.currency().name());
      statement.setLong(4, request.price());
      statement.setInt(5, request.quantity());
      statement.setInt(6, request.quantity());
      statement.setString(7, request.item().registryId());
      statement.setBytes(8, blob);
      statement.setString(9, "{\"payloadHash\":\"" + json(request.item().payloadHash()) + "\"}");
      statement.setString(10, request.remark());
      statement.setString(11, request.item().payloadHash());
      statement.setInt(12, request.quantity());
      statement.setInt(13, request.quantity());
      statement.executeUpdate();
      return readListing(connection, generatedId(statement));
    }
  }

  public Listing createSupplyListing(SupplyListingRequest request) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(request.ownerId(), "ownerId");
    Objects.requireNonNull(request.currency(), "currency");
    Objects.requireNonNull(request.itemTemplate(), "itemTemplate");
    if (request.world() == null || request.world().isBlank()) {
      throw new ServiceException("invalid_supply_location", "Supply world is required");
    }
    requireKey(request.idempotencyKey());
    if (request.price() < 1 || request.batchSize() < 1 || request.maxStock() < 1
        || request.batchSize() > request.maxStock()
        || request.maxStock() > ItemEnvelope.MAX_COUNT) {
      throw new ServiceException("invalid_supply_settings", "Supply listing settings are invalid");
    }
    ItemEnvelope template = withCount(request.itemTemplate(), 1);
    try {
      return database.inTransaction(connection -> {
        try (PreparedStatement operation = connection.prepareStatement(
            "INSERT INTO inventory_operations"
                + " (user_id,idempotency_key,action,state,slot_index,item_fingerprint,quantity,result_json)"
                + " VALUES (?,?,'MARKET_SUPPLY_LIST','PENDING',-1,?,?,?)")) {
          operation.setLong(1, request.ownerUserId());
          operation.setString(2, request.idempotencyKey());
          operation.setString(3, template.payloadHash());
          operation.setInt(4, request.maxStock());
          operation.setString(5, "{}");
          operation.executeUpdate();
        }
        long listingId;
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO market_listings"
                + " (seller_user_id,seller_uuid,currency,price,quantity,quantity_total,"
                + "item_material,raw_item_blob,item_meta_json,remark,item_hash,escrow_total,"
                + "escrow_remaining,status,market_side,trade_mode,source_mode,supply_world,"
                + "supply_x,supply_y,supply_z,supply_batch_size,supply_max_stock,"
                + "supply_access_protected)"
                + " VALUES (?,?,?,?,0,?,?,?,?,?,?,0,0,'SUPPLY_EMPTY','SELL','DIRECT','SUPPLY',"
                + "?,?,?,?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS)) {
          statement.setLong(1, request.ownerUserId());
          statement.setString(2, request.ownerId().toString());
          statement.setString(3, request.currency().name());
          statement.setLong(4, request.price());
          statement.setInt(5, request.maxStock());
          statement.setString(6, template.registryId());
          statement.setBytes(7, envelopes.encode(template));
          statement.setString(8, "{\"payloadHash\":\"" + json(template.payloadHash()) + "\"}");
          statement.setString(9, request.remark());
          statement.setString(10, template.payloadHash());
          statement.setString(11, request.world());
          statement.setInt(12, request.x());
          statement.setInt(13, request.y());
          statement.setInt(14, request.z());
          statement.setInt(15, request.batchSize());
          statement.setInt(16, request.maxStock());
          statement.setBoolean(17, request.accessProtected());
          statement.executeUpdate();
          listingId = generatedId(statement);
        }
        try (PreparedStatement operation = connection.prepareStatement(
            "UPDATE inventory_operations SET state='SUCCESS',reference_id=?,"
                + "updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND idempotency_key=?"
                + " AND action='MARKET_SUPPLY_LIST' AND state='PENDING'")) {
          operation.setLong(1, listingId);
          operation.setLong(2, request.ownerUserId());
          operation.setString(3, request.idempotencyKey());
          if (operation.executeUpdate() != 1) {
            throw new ServiceException("idempotency_conflict", "Supply listing operation changed");
          }
        }
        return readListing(connection, listingId);
      });
    } catch (RuntimeException failure) {
      Listing replay = replaySupplyListing(request, template);
      if (replay != null) return replay;
      throw failure;
    }
  }

  private Listing replaySupplyListing(SupplyListingRequest request, ItemEnvelope template) {
    return replaySupplyListing(new SupplyListingReplayRequest(
        request.ownerUserId(), request.currency(), request.price(), template.payloadHash(),
        request.world(), request.x(), request.y(), request.z(), request.batchSize(),
        request.maxStock(), request.accessProtected(), request.idempotencyKey(), request.remark()));
  }

  public Listing replaySupplyListing(SupplyListingReplayRequest request) {
    return database.withConnection(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT o.action,o.state,o.reference_id,l.currency,l.price,l.item_hash,"
              + "l.supply_world,l.supply_x,l.supply_y,l.supply_z,l.supply_batch_size,"
              + "l.supply_max_stock,l.supply_access_protected,l.remark FROM inventory_operations o "
              + "LEFT JOIN market_listings l ON l.id=o.reference_id "
              + "WHERE o.user_id=? AND o.idempotency_key=?")) {
        statement.setLong(1, request.ownerUserId());
        statement.setString(2, request.idempotencyKey());
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next()) return null;
          if (!"MARKET_SUPPLY_LIST".equals(result.getString(1))
              || !"SUCCESS".equals(result.getString(2))
              || result.getObject(3) == null
              || !request.currency().name().equals(result.getString(4))
              || request.price() != result.getLong(5)
              || !request.expectedPayloadHash().equals(result.getString(6))
              || !request.world().equals(result.getString(7))
              || request.x() != result.getInt(8) || request.y() != result.getInt(9)
              || request.z() != result.getInt(10) || request.batchSize() != result.getInt(11)
              || request.maxStock() != result.getInt(12)
              || request.accessProtected() != result.getBoolean(13)
              || !Objects.equals(request.remark(), result.getString(14))) {
            throw new ServiceException(
                "idempotency_conflict", "Idempotency request does not match");
          }
          return readListing(connection, result.getLong(3));
        }
      }
    });
  }

  public Listing createBuyListing(BuyListingRequest request) {
    requireKey(request.idempotencyKey());
    if (request.price() < 1 || request.quantity() < 1 || request.quantity() > 64) {
      throw new ServiceException("invalid_listing", "Listing price or quantity is invalid");
    }
    String registryId = normalizeRegistryId(request.itemMaterial());
    byte[] targetPayload = registryId.getBytes(StandardCharsets.UTF_8);
    ItemEnvelope target =
        new ItemEnvelopeService(java.time.Clock.systemUTC(), java.util.Set.of("registry-request"))
            .create(
                "registry-request",
                1,
                new CompatibilityDomain("shared", "registry", "any", 1, "registry-only"),
                registryId,
                1,
                targetPayload,
                Map.of("request", "market-buy"));
    try {
      return database.inTransaction(
          connection -> {
            try (PreparedStatement operation =
                connection.prepareStatement(
                    "INSERT INTO inventory_operations"
                        + " (user_id,idempotency_key,action,state,slot_index,item_fingerprint,quantity,result_json)"
                        + " VALUES (?,?,'MARKET_BUY_LIST','PENDING',-1,?,?,?)")) {
              operation.setLong(1, request.ownerUserId());
              operation.setString(2, request.idempotencyKey());
              operation.setString(3, target.payloadHash());
              operation.setInt(4, request.quantity());
              operation.setString(5, "{}");
              operation.executeUpdate();
            }
            long escrow = Math.multiplyExact(request.price(), request.quantity());
            wallets.applyDelta(
                connection,
                request.ownerUserId(),
                request.currency(),
                -escrow,
                "MARKET_BUY_ESCROW",
                "market-buy-list:" + request.ownerUserId() + ":" + request.idempotencyKey(),
                true);
            long listingId;
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "INSERT INTO market_listings"
                        + " (seller_user_id,seller_uuid,currency,price,quantity,quantity_total,item_material,raw_item_blob,item_meta_json,remark,item_hash,escrow_total,escrow_remaining,status,market_side,trade_mode)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE','BUY','DIRECT')",
                    Statement.RETURN_GENERATED_KEYS)) {
              statement.setLong(1, request.ownerUserId());
              statement.setString(2, request.ownerId().toString());
              statement.setString(3, request.currency().name());
              statement.setLong(4, request.price());
              statement.setInt(5, request.quantity());
              statement.setInt(6, request.quantity());
              statement.setString(7, registryId);
              statement.setBytes(8, envelopes.encode(target));
              statement.setString(9, "{\"registryOnly\":true}");
              statement.setString(10, request.remark());
              statement.setString(11, target.payloadHash());
              statement.setLong(12, escrow);
              statement.setLong(13, escrow);
              statement.executeUpdate();
              listingId = generatedId(statement);
            }
            try (PreparedStatement operation =
                connection.prepareStatement(
                    "UPDATE inventory_operations SET state='SUCCESS',reference_id=?,"
                        + "updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND idempotency_key=?"
                        + " AND action='MARKET_BUY_LIST' AND state='PENDING'")) {
              operation.setLong(1, listingId);
              operation.setLong(2, request.ownerUserId());
              operation.setString(3, request.idempotencyKey());
              if (operation.executeUpdate() != 1) {
                throw new ServiceException("idempotency_conflict", "Listing operation changed");
              }
            }
            return readListing(connection, listingId);
          });
    } catch (RuntimeException failure) {
      Listing replay = replayBuyListing(request.ownerUserId(), request.idempotencyKey());
      if (replay != null) return replay;
      throw failure;
    }
  }

  private Listing replayBuyListing(long userId, String idempotencyKey) {
    return database.withConnection(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT action,state,reference_id FROM inventory_operations"
                      + " WHERE user_id=? AND idempotency_key=?")) {
            statement.setLong(1, userId);
            statement.setString(2, idempotencyKey);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) return null;
              if (!"MARKET_BUY_LIST".equals(result.getString(1))) {
                throw new ServiceException(
                    "idempotency_conflict", "Idempotency key belongs to another action");
              }
              if (!"SUCCESS".equals(result.getString(2))) {
                throw new ServiceException(
                    "inventory_outcome_unknown", "Listing operation requires reconciliation");
              }
              return readListing(connection, result.getLong(3));
            }
          }
        });
  }

  private static String normalizeRegistryId(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ServiceException("invalid_item", "Item material is required");
    }
    String value = raw.trim().toLowerCase(Locale.ROOT);
    if (!value.contains(":")) value = "minecraft:" + value;
    if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
      throw new ServiceException("invalid_item", "Item material is invalid");
    }
    return value;
  }

  private static String normalizeChoice(String raw, String fallback, Set<String> allowed) {
    String value = raw == null || raw.isBlank() ? fallback : raw.trim().toUpperCase(Locale.ROOT);
    if (!allowed.contains(value)) {
      throw new ServiceException("invalid_market_settings", "Unsupported market setting: " + value);
    }
    return value;
  }

  private static String normalizeNullable(String raw, int maximumLength, String errorCode) {
    if (raw == null || raw.isBlank()) return null;
    String value = raw.trim();
    if (value.length() > maximumLength) {
      throw new ServiceException(errorCode, "Market display setting is too long");
    }
    return value;
  }

  private static boolean sameAuctionConfiguration(
      AuctionDetails current, String tradeMode, String algorithm, long start, long increment,
      Instant publicEnd, String paramsJson) {
    if (!Objects.equals(current.tradeMode(), tradeMode)) return false;
    if (!"AUCTION".equals(tradeMode)) return true;
    return Objects.equals(current.algorithm(), algorithm)
        && Objects.equals(current.startPrice(), start)
        && Objects.equals(current.minIncrement(), increment)
        && Objects.equals(current.publicEndAt(), publicEnd)
        && Objects.equals(
            MarketAlgorithmRegistry.toJson(MarketAlgorithmRegistry.parseParams(current.paramsJson())),
            MarketAlgorithmRegistry.toJson(MarketAlgorithmRegistry.parseParams(paramsJson)));
  }

  private static void validateTags(Connection connection, List<String> tags) throws SQLException {
    if (tags.isEmpty()) return;
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT enabled FROM market_tags WHERE code=?")) {
      for (String tag : tags) {
        statement.setString(1, tag);
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next() || !result.getBoolean(1)) {
            throw new ServiceException("invalid_market_tags", "Market tag is unavailable: " + tag);
          }
        }
      }
    }
  }

  private static void replaceListingTags(Connection connection, long listingId, List<String> tags)
      throws SQLException {
    try (PreparedStatement delete = connection.prepareStatement(
        "DELETE FROM market_listing_tags WHERE listing_id=?")) {
      delete.setLong(1, listingId);
      delete.executeUpdate();
    }
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO market_listing_tags (listing_id,tag_code,source,position) VALUES (?,?,'MANUAL',?)")) {
      for (int index = 0; index < tags.size(); index++) {
        insert.setLong(1, listingId);
        insert.setString(2, tags.get(index));
        insert.setInt(3, index);
        insert.addBatch();
      }
      insert.executeBatch();
    }
  }

  private static ItemEnvelope withCount(ItemEnvelope source, int count) {
    return new ItemEnvelope(
        source.schemaVersion(), source.codec(), source.codecVersion(),
        source.compatibilityDomain(), source.registryId(), count,
        source.payloadEncoding(), source.payload(), source.payloadHash(),
        source.summary(), source.createdAt());
  }

  public List<Listing> listings(boolean includeInactive) {
    return database.withConnection(
        connection -> {
          String sql =
              "SELECT id,seller_user_id,seller_uuid,currency,price,quantity,raw_item_blob,"
                  + "remark,status,market_side,escrow_total,escrow_remaining FROM market_listings"
                  + (includeInactive ? "" : " WHERE status='ACTIVE'")
                  + " ORDER BY id DESC";
          try (PreparedStatement statement = connection.prepareStatement(sql);
              ResultSet result = statement.executeQuery()) {
            List<Listing> values = new ArrayList<>();
            while (result.next()) {
              values.add(
                  new Listing(
                      result.getLong(1),
                      result.getLong(2),
                      UUID.fromString(result.getString(3)),
                      CurrencyType.valueOf(result.getString(4)),
                      result.getLong(5),
                      result.getInt(6),
                      envelopes.decode(result.getBytes(7)),
                      result.getString(8),
                      result.getString(9),
                      result.getString(10),
                      result.getLong(11),
                      result.getLong(12)));
            }
            return List.copyOf(values);
          }
        });
  }

  public MarketQuote quoteListing(long buyerUserId, long listingId, int quantity) {
    if (quantity < 1) throw new ServiceException("invalid_quantity", "Quantity is invalid");
    return database.withConnection(
        connection -> {
          Listing listing = readListing(connection, listingId);
          AuctionDetails auction = readAuctionDetails(connection, listingId);
          boolean dutch = isDutchAuction(auction);
          if ("AUCTION".equals(auction.tradeMode()) && !dutch) {
            throw new ServiceException("auction_requires_bid", "Auction must be completed by bidding");
          }
          if (dutch && quantity != 1) {
            throw new ServiceException("invalid_quantity", "Dutch auctions are sold as one lot");
          }
          if (dutch && (auction.endAt() == null || !auction.endAt().isAfter(Instant.now()))) {
            throw new ServiceException("auction_unavailable", "Dutch auction has ended");
          }
          if (!listing.status().equals("ACTIVE") || listing.quantity() < quantity) {
            throw new ServiceException("listing_unavailable", "Listing is unavailable");
          }
          if (listing.sellerUserId() == buyerUserId) {
            throw new ServiceException("self_trade", "Seller cannot buy the same listing");
          }
          long unitPrice = dutch ? dutchPrice(auction, Instant.now()) : listing.price();
          AdvancedListing advanced = readAdvancedListing(connection, listingId);
          MarketAlgorithmRegistry.DynamicPriceQuote dynamicQuote =
              !dutch && advanced.dynamicPricingEnabled()
                  ? dynamicQuote(advanced, unitPrice, quantity) : null;
          long total = dynamicQuote == null
              ? Math.multiplyExact(unitPrice, quantity) : dynamicQuote.totalAmount();
          long first = dynamicQuote == null ? unitPrice : dynamicQuote.firstUnitPrice();
          long last = dynamicQuote == null ? unitPrice : dynamicQuote.lastUnitPrice();
          long average = dynamicQuote == null ? unitPrice : dynamicQuote.averageUnitPrice();
          return new MarketQuote(
              listing.id(),
              listing.currency(),
              listing.side(),
              average,
              quantity,
              total,
              total,
              total,
              0L,
              0L,
              first,
              last,
              average,
              dynamicQuote != null,
              dynamicQuote == null ? "ORDER_FIXED" : dynamicQuote.pricingMode().name(),
              advanced.dynamicDemandScore(),
              dynamicQuote == null ? advanced.dynamicDemandScore() : dynamicQuote.nextDemandScore(),
              dynamicQuote == null ? unitPrice : dynamicQuote.nextUnitPrice());
        });
  }

  private static MarketAlgorithmRegistry.DynamicPriceQuote dynamicQuote(
      AdvancedListing settings, long fallbackPrice, int quantity) {
    return MarketAlgorithmRegistry.computeDynamicPriceQuote(
        MarketAlgorithmRegistry.DynamicAlgorithmType.fromRaw(settings.dynamicAlgorithm()),
        MarketAlgorithmRegistry.DynamicPricingMode.fromRaw(settings.dynamicPricingMode()),
        settings.dynamicBasePrice() == null ? fallbackPrice : settings.dynamicBasePrice(),
        settings.dynamicDemandScore(),
        quantity,
        settings.dynamicPriceStep() == null ? 1L : settings.dynamicPriceStep(),
        settings.dynamicFloorPrice(),
        settings.dynamicCapPrice(),
        MarketAlgorithmRegistry.parseParams(settings.dynamicParamsJson()));
  }

  public List<MarketPricePoint> marketPriceTrend(long listingId, int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, 100));
    return database.withConnection(
        connection -> {
          readListing(connection, listingId);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id,unit_price,quantity,created_at FROM market_trades"
                      + " WHERE listing_id=? AND status IN ('PAID','SETTLED')"
                      + " ORDER BY id DESC LIMIT ?")) {
            statement.setLong(1, listingId);
            statement.setInt(2, boundedLimit);
            try (ResultSet result = statement.executeQuery()) {
              List<MarketPricePoint> values = new ArrayList<>();
              while (result.next()) {
                values.add(
                    new MarketPricePoint(
                        result.getLong(1), result.getLong(2), result.getInt(3), instant(result, 4)));
              }
              return List.copyOf(values);
            }
          }
        });
  }

  public Listing pauseListing(long sellerUserId, long listingId) {
    return transitionOwnedListing(sellerUserId, listingId, "ACTIVE", "PAUSED", "paused_at");
  }

  public Listing resumeListing(long sellerUserId, long listingId) {
    return transitionOwnedListing(sellerUserId, listingId, "PAUSED", "ACTIVE", null);
  }

  private Listing transitionOwnedListing(
      long sellerUserId, long listingId, String expectedStatus, String nextStatus, String timeColumn) {
    return database.inTransaction(
        connection -> {
          Listing listing = readOwnedListing(connection, sellerUserId, listingId);
          if ("AUCTION".equals(readAuctionDetails(connection, listingId).tradeMode())) {
            throw new ServiceException("auction_locked", "Auction state cannot be paused or resumed");
          }
          if (listing.status().equals(nextStatus)) return listing;
          if (!listing.status().equals(expectedStatus)) {
            throw new ServiceException("listing_state_invalid", "Listing state cannot be changed");
          }
          String timeUpdate = timeColumn == null ? ",paused_at=NULL" : "," + timeColumn + "=CURRENT_TIMESTAMP";
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE market_listings SET status=?" + timeUpdate
                      + " WHERE id=? AND seller_user_id=? AND status=?")) {
            statement.setString(1, nextStatus);
            statement.setLong(2, listingId);
            statement.setLong(3, sellerUserId);
            statement.setString(4, expectedStatus);
            if (statement.executeUpdate() != 1) {
              throw new ServiceException("listing_conflict", "Listing changed concurrently");
            }
          }
          return readListing(connection, listingId);
        });
  }

  public Listing updateListingPrice(long sellerUserId, long listingId, long price) {
    return updateListingSettings(
        sellerUserId, listingId, price, null, null, false, null, null, null);
  }

  public Listing updateListingSettings(
      long sellerUserId,
      long listingId,
      long price,
      CurrencyType currency,
      String remark) {
    return updateListingSettings(
        sellerUserId, listingId, price, currency, remark, true, null, null, null);
  }

  public Listing updateListingSettings(
      long sellerUserId,
      long listingId,
      long price,
      CurrencyType currency,
      String remark,
      Integer supplyBatchSize,
      Integer supplyMaxStock,
      Boolean supplyAccessProtected) {
    return updateListingSettings(
        sellerUserId, listingId, price, currency, remark, true,
        supplyBatchSize, supplyMaxStock, supplyAccessProtected);
  }

  public Listing updateAdvancedListingSettings(
      long sellerUserId, long listingId, AdvancedListingUpdate update) {
    Objects.requireNonNull(update, "update");
    String tradeMode = normalizeChoice(update.tradeMode(), "DIRECT", Set.of("DIRECT", "AUCTION"));
    boolean dynamic = update.dynamicPricingEnabled() && "DIRECT".equals(tradeMode);
    String dynamicAlgorithm = normalizeChoice(
        update.dynamicAlgorithm(), "LINEAR_DEMAND_V1",
        Set.of("LINEAR_DEMAND_V1", "DIMINISHING_RETURN_V1", "LOG_SMOOTH_V1",
            "EXPONENTIAL_DEFENSE_V1", "THRESHOLD_STEP_V1", "ELASTICITY_V1",
            "PANIC_BUYING_V1"));
    String dynamicMode = normalizeChoice(
        update.dynamicPricingMode(), "ORDER_FIXED", Set.of("ORDER_FIXED", "PER_UNIT_MARGINAL"));
    long base = update.dynamicBasePrice() == null ? update.price() : update.dynamicBasePrice();
    long step = update.dynamicPriceStep() == null ? 1L : update.dynamicPriceStep();
    if (base < 1 || step < 1
        || (update.dynamicFloorPrice() != null && update.dynamicFloorPrice() < 1)
        || (update.dynamicCapPrice() != null && update.dynamicCapPrice() < 1)
        || (update.dynamicFloorPrice() != null && update.dynamicCapPrice() != null
            && update.dynamicFloorPrice() > update.dynamicCapPrice())) {
      throw new ServiceException("invalid_dynamic_pricing", "Dynamic pricing bounds are invalid");
    }
    String dynamicParams = MarketAlgorithmRegistry.toJson(
        MarketAlgorithmRegistry.parseParams(update.dynamicParamsJson()));
    String auctionAlgorithm = normalizeChoice(
        update.auctionAlgorithm(), "ENGLISH_AUCTION_V1",
        Set.of("ENGLISH_AUCTION_V1", "DUTCH_AUCTION_V1", "VICKREY_AUCTION_V1",
            "CANDLE_AUCTION_V1"));
    JsonObject auctionParams = MarketAlgorithmRegistry.parseParams(update.auctionParamsJson());
    Instant publicEnd = update.auctionEndAt();
    Instant actualEnd = publicEnd;
    long auctionStart = update.auctionStartPrice() == null ? update.price() : update.auctionStartPrice();
    long auctionIncrement = update.auctionMinIncrement() == null ? 1L : update.auctionMinIncrement();
    if ("AUCTION".equals(tradeMode)) {
      if (auctionStart < 1 || auctionIncrement < 1 || publicEnd == null
          || publicEnd.isBefore(Instant.now().plusSeconds(30))) {
        throw new ServiceException("invalid_auction", "Auction parameters are invalid");
      }
      if ("DUTCH_AUCTION_V1".equals(auctionAlgorithm)) {
        long floor = MarketAlgorithmRegistry.getLongParam(auctionParams, "floorPrice", 1L);
        if (floor < 1 || floor > auctionStart) {
          throw new ServiceException("invalid_auction", "Dutch auction floor price is invalid");
        }
      }
      if ("CANDLE_AUCTION_V1".equals(auctionAlgorithm)) {
        int extension = Math.toIntExact(Math.min(86_400L, Math.max(0L,
            MarketAlgorithmRegistry.getLongParam(auctionParams, "maxExtensionSeconds", 0L))));
        actualEnd = MarketAlgorithmRegistry.computeCandleActualEnd(
            LocalDateTime.ofInstant(publicEnd, ZoneOffset.UTC), extension).toInstant(ZoneOffset.UTC);
      }
    }
    String displayName = normalizeNullable(update.displayNameOverride(), 128, "invalid_display_name");
    String requestedDisplayMaterial =
        normalizeNullable(update.displayMaterial(), 128, "invalid_display_material");
    String displayMaterial = requestedDisplayMaterial == null
        ? null : normalizeRegistryId(requestedDisplayMaterial);
    String displayIcon = normalizeNullable(update.displayIconPath(), 512, "invalid_display_icon");
    if (displayIcon != null && (displayIcon.contains("..") || displayIcon.startsWith("/")
        || displayIcon.contains(":"))) {
      throw new ServiceException("invalid_display_icon", "Display icon path is invalid");
    }
    List<String> tags = update.tags() == null ? List.of() : update.tags().stream()
        .filter(Objects::nonNull).map(value -> value.trim().toLowerCase(Locale.ROOT))
        .filter(value -> !value.isBlank()).distinct().toList();
    if (tags.size() > 16 || tags.stream().anyMatch(value -> !value.matches("[a-z0-9_.-]{1,64}"))) {
      throw new ServiceException("invalid_market_tags", "Listing tags are invalid");
    }
    Instant resolvedEnd = actualEnd;
    return database.inTransaction(connection -> {
      Listing listing = readOwnedListing(connection, sellerUserId, listingId);
      requireMutableListing(listing);
      CurrencyType nextCurrency = update.currency() == null ? listing.currency() : update.currency();
      if ("BUY".equals(listing.side()) && nextCurrency != listing.currency()) {
        throw new ServiceException(
            "listing_currency_locked", "Buy listing currency cannot be changed");
      }
      String remark = normalizeNullable(update.remark(), 500, "invalid_remark");
      SupplySettings supplySettings = readSupplySettings(connection, listingId);
      boolean supplyTouched = update.supplyBatchSize() != null || update.supplyMaxStock() != null
          || update.supplyAccessProtected() != null;
      if (!"SUPPLY".equals(supplySettings.sourceMode()) && supplyTouched) {
        throw new ServiceException("supply_not_configured", "Listing is not a supply listing");
      }
      Integer supplyBatch = supplySettings.batchSize();
      Integer supplyMaximum = supplySettings.maxStock();
      boolean supplyProtected = supplySettings.accessProtected();
      if ("SUPPLY".equals(supplySettings.sourceMode())) {
        int batch = update.supplyBatchSize() == null
            ? Math.max(1, supplySettings.batchSize()) : update.supplyBatchSize();
        int maximum = update.supplyMaxStock() == null
            ? Math.max(1, supplySettings.maxStock()) : update.supplyMaxStock();
        if (batch < 1 || maximum < 1 || batch > ItemEnvelope.MAX_COUNT
            || maximum > ItemEnvelope.MAX_COUNT) {
          throw new ServiceException("invalid_supply_settings", "Supply stock settings are invalid");
        }
        supplyBatch = Math.min(batch, maximum);
        supplyMaximum = maximum;
        supplyProtected = update.supplyAccessProtected() == null
            ? supplySettings.accessProtected() : update.supplyAccessProtected();
      }
      long escrow = listing.escrowRemaining();
      long escrowDelta = 0L;
      if ("BUY".equals(listing.side())) {
        escrow = Math.multiplyExact(update.price(), listing.quantity());
        escrowDelta = escrow - listing.escrowRemaining();
        if (escrowDelta != 0) {
          wallets.applyDelta(
              connection, listing.sellerUserId(), listing.currency(), -escrowDelta,
              "MARKET_BUY_REPRICE", "market-buy-reprice:" + listing.id() + ":" + UUID.randomUUID(),
              escrowDelta > 0);
        }
      }
      AuctionDetails existing = readAuctionDetails(connection, listingId);
      boolean auctionChanged = !sameAuctionConfiguration(
          existing, tradeMode, auctionAlgorithm, auctionStart,
          auctionIncrement, publicEnd, update.auctionParamsJson());
      if (auctionChanged) {
        try (PreparedStatement bids = connection.prepareStatement(
            "SELECT COUNT(*) FROM market_bids WHERE listing_id=?")) {
          bids.setLong(1, listingId);
          try (ResultSet result = bids.executeQuery()) {
            if (result.next() && result.getLong(1) > 0) {
              throw new ServiceException("auction_locked", "Auction already has bids");
            }
          }
        }
      }
      validateTags(connection, tags);
      try (PreparedStatement baseUpdate = connection.prepareStatement(
          "UPDATE market_listings SET price=?,currency=?,remark=?,escrow_total=escrow_total+?,"
              + "escrow_remaining=?,supply_batch_size=?,supply_max_stock=?,"
              + "supply_access_protected=? WHERE id=? AND seller_user_id=? "
              + "AND status IN ('ACTIVE','PAUSED')")) {
        baseUpdate.setLong(1, update.price());
        baseUpdate.setString(2, nextCurrency.name());
        baseUpdate.setString(3, remark);
        baseUpdate.setLong(4, escrowDelta);
        baseUpdate.setLong(5, escrow);
        if (supplyBatch == null) baseUpdate.setObject(6, null); else baseUpdate.setInt(6, supplyBatch);
        if (supplyMaximum == null) baseUpdate.setObject(7, null); else baseUpdate.setInt(7, supplyMaximum);
        baseUpdate.setBoolean(8, supplyProtected);
        baseUpdate.setLong(9, listingId);
        baseUpdate.setLong(10, sellerUserId);
        if (baseUpdate.executeUpdate() != 1) {
          throw new ServiceException("listing_conflict", "Listing changed concurrently");
        }
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "UPDATE market_listings SET display_name_override=?,display_material=?,display_icon_path=?,"
              + "trade_mode=?,dynamic_pricing_enabled=?,dynamic_algorithm=?,dynamic_pricing_mode=?,"
              + "dynamic_base_price=?,dynamic_floor_price=?,dynamic_cap_price=?,dynamic_price_step=?,"
              + "dynamic_params_json=?,auction_algorithm=?,auction_start_price=?,auction_min_increment=?,"
              + "auction_started_at=CASE WHEN ?<>'AUCTION' THEN NULL WHEN ? THEN CURRENT_TIMESTAMP "
              + "ELSE auction_started_at END,"
              + "auction_public_end_at=?,auction_end_at=?,auction_params_json=? "
              + "WHERE id=? AND seller_user_id=? AND status IN ('ACTIVE','PAUSED')")) {
        statement.setString(1, displayName);
        statement.setString(2, displayMaterial);
        statement.setString(3, displayIcon);
        statement.setString(4, tradeMode);
        statement.setBoolean(5, dynamic);
        statement.setString(6, dynamicAlgorithm);
        statement.setString(7, dynamicMode);
        if (dynamic) statement.setLong(8, base); else statement.setObject(8, null);
        if (dynamic && update.dynamicFloorPrice() != null) statement.setLong(9, update.dynamicFloorPrice());
        else statement.setObject(9, null);
        if (dynamic && update.dynamicCapPrice() != null) statement.setLong(10, update.dynamicCapPrice());
        else statement.setObject(10, null);
        if (dynamic) statement.setLong(11, step); else statement.setObject(11, null);
        statement.setString(12, dynamic ? dynamicParams : null);
        statement.setString(13, auctionAlgorithm);
        if ("AUCTION".equals(tradeMode)) statement.setLong(14, auctionStart); else statement.setObject(14, null);
        if ("AUCTION".equals(tradeMode)) statement.setLong(15, auctionIncrement); else statement.setObject(15, null);
        statement.setString(16, tradeMode);
        statement.setBoolean(17, auctionChanged);
        if ("AUCTION".equals(tradeMode)) statement.setTimestamp(18, Timestamp.from(publicEnd)); else statement.setObject(18, null);
        if ("AUCTION".equals(tradeMode)) statement.setTimestamp(19, Timestamp.from(resolvedEnd)); else statement.setObject(19, null);
        statement.setString(20, "AUCTION".equals(tradeMode) ? MarketAlgorithmRegistry.toJson(auctionParams) : null);
        statement.setLong(21, listingId);
        statement.setLong(22, sellerUserId);
        if (statement.executeUpdate() != 1) throw new ServiceException("listing_conflict", "Listing changed concurrently");
      }
      replaceListingTags(connection, listingId, tags);
      return readListing(connection, listingId);
    });
  }

  private Listing updateListingSettings(
      long sellerUserId,
      long listingId,
      long price,
      CurrencyType requestedCurrency,
      String remark,
      boolean updateRemark,
      Integer requestedSupplyBatchSize,
      Integer requestedSupplyMaxStock,
      Boolean requestedSupplyAccessProtected) {
    if (price < 1) throw new ServiceException("invalid_price", "Listing price is invalid");
    String normalizedRemark = remark == null || remark.isBlank() ? null : remark.trim();
    if (normalizedRemark != null && normalizedRemark.length() > 500) {
      throw new ServiceException("invalid_remark", "Listing remark is too long");
    }
    return database.inTransaction(
        connection -> {
          Listing listing = readOwnedListing(connection, sellerUserId, listingId);
          requireMutableListing(listing);
          if ("AUCTION".equals(readAuctionDetails(connection, listingId).tradeMode())) {
            throw new ServiceException("auction_locked", "Auction pricing is locked");
          }
          CurrencyType nextCurrency =
              requestedCurrency == null ? listing.currency() : requestedCurrency;
          if (listing.side().equals("BUY") && nextCurrency != listing.currency()) {
            throw new ServiceException(
                "listing_currency_locked", "Buy listing currency cannot be changed");
          }
          SupplySettings supply = readSupplySettings(connection, listingId);
          boolean supplyTouched = requestedSupplyBatchSize != null
              || requestedSupplyMaxStock != null || requestedSupplyAccessProtected != null;
          if (!"SUPPLY".equals(supply.sourceMode()) && supplyTouched) {
            throw new ServiceException(
                "supply_not_configured", "Listing is not a supply listing");
          }
          Integer nextSupplyBatch = supply.batchSize();
          Integer nextSupplyMax = supply.maxStock();
          boolean nextSupplyProtected = supply.accessProtected();
          if ("SUPPLY".equals(supply.sourceMode())) {
            int batch = requestedSupplyBatchSize == null
                ? Math.max(1, supply.batchSize()) : requestedSupplyBatchSize;
            int maximum = requestedSupplyMaxStock == null
                ? Math.max(1, supply.maxStock()) : requestedSupplyMaxStock;
            if (batch < 1 || maximum < 1
                || batch > ItemEnvelope.MAX_COUNT || maximum > ItemEnvelope.MAX_COUNT) {
              throw new ServiceException(
                  "invalid_supply_settings", "Supply stock settings are invalid");
            }
            nextSupplyBatch = Math.min(batch, maximum);
            nextSupplyMax = maximum;
            nextSupplyProtected = requestedSupplyAccessProtected == null
                ? supply.accessProtected() : requestedSupplyAccessProtected;
          }
          long nextEscrow = listing.escrowRemaining();
          long escrowDelta = 0L;
          if (listing.side().equals("BUY")) {
            nextEscrow = Math.multiplyExact(price, listing.quantity());
            escrowDelta = nextEscrow - listing.escrowRemaining();
            if (escrowDelta != 0) {
              wallets.applyDelta(
                  connection,
                  listing.sellerUserId(),
                  listing.currency(),
                  -escrowDelta,
                  "MARKET_BUY_REPRICE",
                  "market-buy-reprice:" + listing.id() + ":" + UUID.randomUUID(),
                  escrowDelta > 0);
            }
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE market_listings SET price=?,currency=?,remark=CASE WHEN ? THEN ? ELSE remark END,"
                      + "escrow_total=escrow_total+?,escrow_remaining=?,supply_batch_size=?,"
                      + "supply_max_stock=?,supply_access_protected=?"
                      + " WHERE id=? AND seller_user_id=?"
                      + " AND status IN ('ACTIVE','PAUSED')")) {
            statement.setLong(1, price);
            statement.setString(2, nextCurrency.name());
            statement.setBoolean(3, updateRemark);
            statement.setString(4, normalizedRemark);
            statement.setLong(5, escrowDelta);
            statement.setLong(6, nextEscrow);
            if (nextSupplyBatch == null) statement.setObject(7, null);
            else statement.setInt(7, nextSupplyBatch);
            if (nextSupplyMax == null) statement.setObject(8, null);
            else statement.setInt(8, nextSupplyMax);
            statement.setBoolean(9, nextSupplyProtected);
            statement.setLong(10, listingId);
            statement.setLong(11, sellerUserId);
            if (statement.executeUpdate() != 1) {
              throw new ServiceException("listing_conflict", "Listing changed concurrently");
            }
          }
          return readListing(connection, listingId);
        });
  }

  private SupplySettings readSupplySettings(Connection connection, long listingId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT source_mode,supply_batch_size,supply_max_stock,supply_access_protected "
            + "FROM market_listings WHERE id=?")) {
      statement.setLong(1, listingId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new ServiceException("not_found", "Market listing was not found");
        Integer batch = result.getObject(2) == null ? null : result.getInt(2);
        Integer maximum = result.getObject(3) == null ? null : result.getInt(3);
        return new SupplySettings(result.getString(1), batch, maximum, result.getBoolean(4));
      }
    }
  }

  public Listing updateListingRemark(long sellerUserId, long listingId, String remark) {
    String normalized = remark == null || remark.isBlank() ? null : remark.trim();
    if (normalized != null && normalized.length() > 500) {
      throw new ServiceException("invalid_remark", "Listing remark is too long");
    }
    return database.inTransaction(
        connection -> {
          Listing listing = readOwnedListing(connection, sellerUserId, listingId);
          requireMutableListing(listing);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE market_listings SET remark=? WHERE id=? AND seller_user_id=?"
                      + " AND status IN ('ACTIVE','PAUSED')")) {
            statement.setString(1, normalized);
            statement.setLong(2, listingId);
            statement.setLong(3, sellerUserId);
            if (statement.executeUpdate() != 1) {
              throw new ServiceException("listing_conflict", "Listing changed concurrently");
            }
          }
          return readListing(connection, listingId);
        });
  }

  public AssetPathUpdate updateListingIcon(
      long sellerUserId, long listingId, String assetPath) {
    requireManagedAssetPath(assetPath, "/uploads/listing-icons/");
    return database.inTransaction(
        connection -> {
          Listing listing = readOwnedListing(connection, sellerUserId, listingId);
          requireMutableListing(listing);
          String previous;
          try (PreparedStatement statement = connection.prepareStatement(
              "SELECT display_icon_path FROM market_listings WHERE id=?")) {
            statement.setLong(1, listingId);
            try (ResultSet result = statement.executeQuery()) {
              result.next();
              previous = result.getString(1);
            }
          }
          try (PreparedStatement statement = connection.prepareStatement(
              "UPDATE market_listings SET display_icon_path=? WHERE id=? AND seller_user_id=?")) {
            statement.setString(1, assetPath);
            statement.setLong(2, listingId);
            statement.setLong(3, sellerUserId);
            if (statement.executeUpdate() != 1) {
              throw new ServiceException("listing_conflict", "Listing changed concurrently");
            }
          }
          return new AssetPathUpdate(listingId, previous, assetPath);
        });
  }

  public Listing unlist(long sellerUserId, long listingId) {
    return database.inTransaction(
        connection -> {
          Listing listing = readOwnedListing(connection, sellerUserId, listingId);
          if (listing.status().equals("UNLISTED")) return listing;
          requireMutableListing(listing);
          AuctionDetails auction = readAuctionDetails(connection, listingId);
          if ("AUCTION".equals(auction.tradeMode()) && hasPendingAuctionBids(connection, listingId)) {
            throw new ServiceException("auction_locked", "Auction cannot be unlisted after bidding");
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE market_listings SET status='UNLISTED',unlisted_at=CURRENT_TIMESTAMP,"
                      + "paused_at=NULL,escrow_remaining=0 WHERE id=? AND seller_user_id=?"
                      + " AND status IN ('ACTIVE','PAUSED')")) {
            statement.setLong(1, listingId);
            statement.setLong(2, sellerUserId);
            if (statement.executeUpdate() != 1) {
              throw new ServiceException("listing_conflict", "Listing changed concurrently");
            }
          }
          if (listing.side().equals("BUY") && listing.escrowRemaining() > 0) {
            wallets.applyDelta(
                connection,
                listing.sellerUserId(),
                listing.currency(),
                listing.escrowRemaining(),
                "MARKET_BUY_REFUND_ESCROW",
                "market-buy-unlist:" + listing.id(),
                false);
          } else if (listing.quantity() > 0) {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "INSERT INTO market_item_deliveries"
                        + " (listing_id,trade_id,target_user_id,target_uuid,target_server_id,item_blob,quantity,delivery_type,next_retry_at)"
                        + " VALUES (?,NULL,?,?,NULL,?,?,'UNLIST',CURRENT_TIMESTAMP)")) {
              statement.setLong(1, listing.id());
              statement.setLong(2, listing.sellerUserId());
              statement.setString(3, listing.sellerId().toString());
              statement.setBytes(4, envelopes.encode(listing.item()));
              statement.setInt(5, listing.quantity());
              statement.executeUpdate();
            }
          }
          return readListing(connection, listingId);
        });
  }

  public Listing forceUnlist(long listingId) {
    Listing listing = listing(listingId);
    return unlist(listing.sellerUserId(), listingId);
  }

  private Listing readOwnedListing(Connection connection, long sellerUserId, long listingId)
      throws SQLException {
    Listing listing = readListing(connection, listingId);
    if (listing.sellerUserId() != sellerUserId) {
      throw new ServiceException("listing_forbidden", "Listing belongs to another seller");
    }
    return listing;
  }

  private static void requireMutableListing(Listing listing) {
    if (!listing.status().equals("ACTIVE") && !listing.status().equals("PAUSED")) {
      throw new ServiceException("listing_state_invalid", "Listing is no longer mutable");
    }
  }

  public MarketTrade buyListing(MarketBuyRequest request) {
    requireKey(request.idempotencyKey());
    if (request.quantity() < 1)
      throw new ServiceException("invalid_quantity", "Quantity is invalid");
    return database.inTransaction(
        connection -> {
          MarketTrade prior =
              findMarketTrade(connection, request.buyerUserId(), request.idempotencyKey());
          if (prior != null) return prior;
          Listing listing = readListing(connection, request.listingId());
          AuctionDetails auction = readAuctionDetails(connection, request.listingId());
          boolean dutch = isDutchAuction(auction);
          if ("AUCTION".equals(auction.tradeMode()) && !dutch) {
            throw new ServiceException("auction_requires_bid", "Auction must be completed by bidding");
          }
          if (dutch && request.quantity() != 1) {
            throw new ServiceException("invalid_quantity", "Dutch auctions are sold as one lot");
          }
          if (dutch && (auction.endAt() == null || !auction.endAt().isAfter(Instant.now()))) {
            throw new ServiceException("auction_unavailable", "Dutch auction has ended");
          }
          if (!listing.side().equals("SELL")) {
            throw new ServiceException("listing_side_invalid", "Listing is not a sell listing");
          }
          if (!listing.status().equals("ACTIVE") || listing.quantity() < request.quantity()) {
            throw new ServiceException("listing_unavailable", "Listing is unavailable");
          }
          if (listing.sellerUserId() == request.buyerUserId()) {
            throw new ServiceException("self_trade", "Seller cannot buy the same listing");
          }
          long baseUnitPrice = dutch ? dutchPrice(auction, Instant.now()) : listing.price();
          AdvancedListing advanced = readAdvancedListing(connection, listing.id());
          MarketAlgorithmRegistry.DynamicPriceQuote dynamicQuote =
              !dutch && advanced.dynamicPricingEnabled()
                  ? dynamicQuote(advanced, baseUnitPrice, request.quantity()) : null;
          long unitPrice = dynamicQuote == null
              ? baseUnitPrice : dynamicQuote.averageUnitPrice();
          long total = dynamicQuote == null
              ? Math.multiplyExact(unitPrice, request.quantity()) : dynamicQuote.totalAmount();
          if (request.expectedUnitPrice() != null
              && request.expectedUnitPrice().longValue() != unitPrice) {
            throw new ServiceException("price_changed", "Listing price changed");
          }
          if (request.expectedBuyerTotal() != null
              && request.expectedBuyerTotal().longValue() != total) {
            throw new ServiceException("price_changed", "Listing total changed");
          }
          String biz = "MARKET-" + UUID.randomUUID();
          wallets.applyDelta(
              connection,
              request.buyerUserId(),
              listing.currency(),
              -total,
              "MARKET_BUY",
              biz + ":buyer",
              true);
          wallets.applyDelta(
              connection,
              listing.sellerUserId(),
              listing.currency(),
              total,
              "MARKET_SELL",
              biz + ":seller",
              false);
          int remaining = listing.quantity() - request.quantity();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE market_listings SET"
                      + " quantity=?,escrow_remaining=?,buyer_user_id=?,buyer_uuid=?,status=?,sold_at=CASE"
                      + " WHEN ?=0 THEN CURRENT_TIMESTAMP ELSE sold_at END,"
                      + "dynamic_demand_score=?,price=? WHERE id=? AND"
                      + " status='ACTIVE' AND quantity=?")) {
            statement.setInt(1, remaining);
            statement.setInt(2, remaining);
            statement.setLong(3, request.buyerUserId());
            statement.setString(4, request.buyerId().toString());
            statement.setString(5, remaining == 0 ? "SOLD" : "ACTIVE");
            statement.setInt(6, remaining);
            statement.setLong(7, dynamicQuote == null
                ? advanced.dynamicDemandScore() : dynamicQuote.nextDemandScore());
            statement.setLong(8, dynamicQuote == null ? listing.price() : dynamicQuote.nextUnitPrice());
            statement.setLong(9, listing.id());
            statement.setInt(10, listing.quantity());
            if (statement.executeUpdate() != 1) {
              throw new ServiceException("listing_conflict", "Listing changed concurrently");
            }
          }
          long tradeId;
          String claimToken = UUID.randomUUID().toString();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO market_trades"
                      + " (listing_id,buyer_user_id,seller_user_id,currency,unit_price,quantity,total_price,buyer_total,seller_receive,idempotency_key,claim_token,status)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,'PAID')",
                  Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, listing.id());
            statement.setLong(2, request.buyerUserId());
            statement.setLong(3, listing.sellerUserId());
            statement.setString(4, listing.currency().name());
            statement.setLong(5, unitPrice);
            statement.setInt(6, request.quantity());
            statement.setLong(7, total);
            statement.setLong(8, total);
            statement.setLong(9, total);
            statement.setString(10, request.idempotencyKey());
            statement.setString(11, claimToken);
            statement.executeUpdate();
            tradeId = generatedId(statement);
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO market_item_deliveries"
                      + " (listing_id,trade_id,target_user_id,target_uuid,target_server_id,item_blob,quantity,delivery_type,next_retry_at)"
                      + " VALUES (?,?,?,?,?,?,?,'BUYER_ITEM',CURRENT_TIMESTAMP)")) {
            statement.setLong(1, listing.id());
            statement.setLong(2, tradeId);
            statement.setLong(3, request.buyerUserId());
            statement.setString(4, request.buyerId().toString());
            statement.setString(5, request.targetServerId());
            statement.setBytes(6, envelopes.encode(listing.item()));
            statement.setInt(7, request.quantity());
            statement.executeUpdate();
          }
          return new MarketTrade(
              tradeId,
              listing.id(),
              request.buyerUserId(),
              listing.sellerUserId(),
              request.quantity(),
              listing.currency(),
              total,
              request.idempotencyKey(),
              "PAID");
        });
  }

  public List<MarketMatch> matchingBuyListings(
      long sellerUserId, ItemEnvelope item, int requestedQuantity) {
    if (requestedQuantity < 1) {
      throw new ServiceException("invalid_quantity", "Quantity is invalid");
    }
    return database.withConnection(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id,seller_user_id,currency,price,quantity FROM market_listings"
                      + " WHERE market_side='BUY' AND status='ACTIVE' AND seller_user_id<>?"
                      + " AND LOWER(item_material)=? ORDER BY price DESC,id ASC")) {
            statement.setLong(1, sellerUserId);
            statement.setString(2, item.registryId().toLowerCase(Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) {
              List<MarketMatch> values = new ArrayList<>();
              while (result.next()) {
                int quantity = Math.min(requestedQuantity, result.getInt(5));
                long total = Math.multiplyExact(result.getLong(4), quantity);
                values.add(
                    new MarketMatch(
                        result.getLong(1),
                        result.getLong(2),
                        CurrencyType.valueOf(result.getString(3)),
                        result.getLong(4),
                        result.getInt(5),
                        quantity,
                        total,
                        0L));
              }
              return List.copyOf(values);
            }
          }
        });
  }

  MarketTrade fulfillBuyListing(
      Connection connection, MarketFulfillRequest request, ItemEnvelope item) throws SQLException {
    requireKey(request.idempotencyKey());
    if (request.quantity() < 1 || item.count() != request.quantity()) {
      throw new ServiceException("invalid_quantity", "Quantity is invalid");
    }
    Listing listing = readListing(connection, request.listingId());
    if (!listing.side().equals("BUY")) {
      throw new ServiceException("listing_side_invalid", "Listing is not a buy listing");
    }
    if (!listing.status().equals("ACTIVE") || listing.quantity() < request.quantity()) {
      throw new ServiceException("listing_unavailable", "Listing is unavailable");
    }
    if (listing.sellerUserId() == request.sellerUserId()) {
      throw new ServiceException("self_trade", "Buyer cannot fulfill the same listing");
    }
    if (!listing.item().registryId().equalsIgnoreCase(item.registryId())) {
      throw new ServiceException("item_mismatch", "Inventory item does not match buy listing");
    }
    long total = Math.multiplyExact(listing.price(), request.quantity());
    if (listing.escrowRemaining() < total) {
      throw new ServiceException("escrow_conflict", "Buy listing escrow is insufficient");
    }
    if (request.expectedUnitPrice() != null
        && request.expectedUnitPrice().longValue() != listing.price()) {
      throw new ServiceException("price_changed", "Listing price changed");
    }
    if (request.expectedBuyerTotal() != null
        && request.expectedBuyerTotal().longValue() != total) {
      throw new ServiceException("price_changed", "Listing total changed");
    }
    wallets.applyDelta(
        connection,
        request.sellerUserId(),
        listing.currency(),
        total,
        "MARKET_BUY_ORDER_FULFILL",
        "market-fulfill:" + request.sellerUserId() + ":" + request.idempotencyKey(),
        false);
    int remaining = listing.quantity() - request.quantity();
    long escrowRemaining = listing.escrowRemaining() - total;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE market_listings SET quantity=?,escrow_remaining=?,buyer_user_id=?,buyer_uuid=?,"
                + "status=?,sold_at=CASE WHEN ?=0 THEN CURRENT_TIMESTAMP ELSE sold_at END"
                + " WHERE id=? AND status='ACTIVE' AND quantity=? AND escrow_remaining=?")) {
      statement.setInt(1, remaining);
      statement.setLong(2, escrowRemaining);
      statement.setLong(3, request.sellerUserId());
      statement.setString(4, request.sellerId().toString());
      statement.setString(5, remaining == 0 ? "SOLD" : "ACTIVE");
      statement.setInt(6, remaining);
      statement.setLong(7, listing.id());
      statement.setInt(8, listing.quantity());
      statement.setLong(9, listing.escrowRemaining());
      if (statement.executeUpdate() != 1) {
        throw new ServiceException("listing_conflict", "Listing changed concurrently");
      }
    }
    long tradeId;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO market_trades"
                + " (listing_id,buyer_user_id,seller_user_id,currency,unit_price,quantity,total_price,buyer_total,seller_receive,idempotency_key,claim_token,status)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,'PAID')",
            Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, listing.id());
      statement.setLong(2, listing.sellerUserId());
      statement.setLong(3, request.sellerUserId());
      statement.setString(4, listing.currency().name());
      statement.setLong(5, listing.price());
      statement.setInt(6, request.quantity());
      statement.setLong(7, total);
      statement.setLong(8, total);
      statement.setLong(9, total);
      statement.setString(10, request.idempotencyKey());
      statement.setString(11, UUID.randomUUID().toString());
      statement.executeUpdate();
      tradeId = generatedId(statement);
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO market_item_deliveries"
                + " (listing_id,trade_id,target_user_id,target_uuid,target_server_id,item_blob,quantity,delivery_type,next_retry_at)"
                + " VALUES (?,?,?,?,NULL,?,?,'SALE',CURRENT_TIMESTAMP)")) {
      statement.setLong(1, listing.id());
      statement.setLong(2, tradeId);
      statement.setLong(3, listing.sellerUserId());
      statement.setString(4, listing.sellerId().toString());
      statement.setBytes(5, envelopes.encode(item));
      statement.setInt(6, request.quantity());
      statement.executeUpdate();
    }
    return new MarketTrade(
        tradeId,
        listing.id(),
        listing.sellerUserId(),
        request.sellerUserId(),
        request.quantity(),
        listing.currency(),
        total,
        request.idempotencyKey(),
        "PAID");
  }

  public MarketTrade marketTrade(long tradeId) {
    return database.withConnection(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id,listing_id,buyer_user_id,seller_user_id,quantity,currency,"
                      + "total_price,idempotency_key,status FROM market_trades WHERE id=?")) {
            statement.setLong(1, tradeId);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) {
                throw new ServiceException("trade_not_found", "Market trade was not found");
              }
              return new MarketTrade(
                  result.getLong(1),
                  result.getLong(2),
                  result.getLong(3),
                  result.getLong(4),
                  result.getInt(5),
                  CurrencyType.valueOf(result.getString(6)),
                  result.getLong(7),
                  result.getString(8),
                  result.getString(9));
            }
          }
        });
  }

  public Product product(long productId) {
    return database.withConnection(connection -> readProduct(connection, productId));
  }

  public Listing listing(long listingId) {
    return database.withConnection(connection -> readListing(connection, listingId));
  }

  /** Returns the active supply source at an exact native block coordinate, if one exists. */
  public SupplyProtection supplyProtectionAt(String world, int x, int y, int z) {
    if (world == null || world.isBlank()) return null;
    return database.withConnection(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT id,seller_uuid,supply_access_protected,status FROM market_listings"
              + " WHERE source_mode='SUPPLY' AND status IN ('ACTIVE','PAUSED','SUPPLY_EMPTY')"
              + " AND supply_world=? AND supply_x=? AND supply_y=? AND supply_z=?"
              + " ORDER BY id DESC LIMIT 1")) {
        statement.setString(1, world);
        statement.setInt(2, x);
        statement.setInt(3, y);
        statement.setInt(4, z);
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next()) return null;
          return new SupplyProtection(
              result.getLong(1), UUID.fromString(result.getString(2)),
              result.getBoolean(3), result.getString(4));
        }
      }
    });
  }

  public AuctionDetails configureAuctionListing(
      long sellerUserId,
      long listingId,
      String algorithm,
      long startPrice,
      long minIncrement,
      Instant endAt,
      String paramsJson) {
    String normalizedAlgorithm =
        algorithm == null ? "ENGLISH_AUCTION_V1" : algorithm.trim().toUpperCase(Locale.ROOT);
    if (!java.util.Set.of(
            "ENGLISH_AUCTION_V1",
            "DUTCH_AUCTION_V1",
            "VICKREY_AUCTION_V1",
            "CANDLE_AUCTION_V1")
        .contains(normalizedAlgorithm)) {
      throw new ServiceException(
          "invalid_auction", "This auction algorithm is not available");
    }
    if (startPrice < 1 || minIncrement < 1 || endAt == null || endAt.isBefore(Instant.now().plusSeconds(30))) {
      throw new ServiceException("invalid_auction", "Auction parameters are invalid");
    }
    JsonObject auctionParams = MarketAlgorithmRegistry.parseParams(paramsJson);
    Instant actualEndAt = endAt;
    if ("CANDLE_AUCTION_V1".equals(normalizedAlgorithm)) {
      int maxExtension =
          Math.toIntExact(
              Math.min(
                  86_400L,
                  Math.max(
                      0L,
                      MarketAlgorithmRegistry.getLongParam(
                          auctionParams, "maxExtensionSeconds", 0L))));
      actualEndAt =
          MarketAlgorithmRegistry.computeCandleActualEnd(
                  LocalDateTime.ofInstant(endAt, ZoneOffset.UTC), maxExtension)
              .toInstant(ZoneOffset.UTC);
    }
    if ("DUTCH_AUCTION_V1".equals(normalizedAlgorithm)) {
      long floorPrice = MarketAlgorithmRegistry.getLongParam(auctionParams, "floorPrice", 1L);
      if (floorPrice < 1 || floorPrice > startPrice) {
        throw new ServiceException("invalid_auction", "Dutch auction floor price is invalid");
      }
    }
    Instant resolvedEndAt = actualEndAt;
    return database.inTransaction(
        connection -> {
          Listing listing = readOwnedListing(connection, sellerUserId, listingId);
          requireMutableListing(listing);
          AuctionDetails currentAuction = readAuctionDetails(connection, listingId);
          try (PreparedStatement bids =
              connection.prepareStatement("SELECT COUNT(*) FROM market_bids WHERE listing_id=?")) {
            bids.setLong(1, listingId);
            try (ResultSet result = bids.executeQuery()) {
              if (result.next() && result.getLong(1) > 0) {
                if ("AUCTION".equals(currentAuction.tradeMode())) return currentAuction;
                throw new ServiceException("auction_locked", "Auction already has bids");
              }
            }
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE market_listings SET trade_mode='AUCTION',price=?,auction_algorithm=?,"
                      + "auction_start_price=?,auction_min_increment=?,auction_started_at=CURRENT_TIMESTAMP,"
                      + "auction_public_end_at=?,auction_end_at=?,auction_params_json=?"
                      + " WHERE id=? AND seller_user_id=? AND status IN ('ACTIVE','PAUSED')")) {
            statement.setLong(1, startPrice);
            statement.setString(2, normalizedAlgorithm);
            statement.setLong(3, startPrice);
            statement.setLong(4, minIncrement);
            statement.setTimestamp(5, Timestamp.from(endAt));
            statement.setTimestamp(6, Timestamp.from(resolvedEndAt));
            statement.setString(7, paramsJson);
            statement.setLong(8, listingId);
            statement.setLong(9, sellerUserId);
            if (statement.executeUpdate() != 1) {
              throw new ServiceException("listing_conflict", "Listing changed concurrently");
            }
          }
          return readAuctionDetails(connection, listingId);
        });
  }

  public AuctionDetails auctionDetails(long listingId) {
    return database.withConnection(connection -> readAuctionDetails(connection, listingId));
  }

  public AdvancedListing advancedListing(long listingId) {
    return database.withConnection(connection -> readAdvancedListing(connection, listingId));
  }

  private AdvancedListing readAdvancedListing(Connection connection, long listingId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
          "SELECT display_name_override,display_material,display_icon_path,"
              + "dynamic_pricing_enabled,dynamic_algorithm,dynamic_pricing_mode,"
              + "dynamic_base_price,dynamic_floor_price,dynamic_cap_price,dynamic_price_step,"
              + "dynamic_demand_score,dynamic_params_json FROM market_listings WHERE id=?")) {
        statement.setLong(1, listingId);
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next()) throw new ServiceException("listing_not_found", "Listing was not found");
          List<String> tags = new ArrayList<>();
          try (PreparedStatement tagQuery = connection.prepareStatement(
              "SELECT tag_code FROM market_listing_tags WHERE listing_id=? ORDER BY position,tag_code")) {
            tagQuery.setLong(1, listingId);
            try (ResultSet tagRows = tagQuery.executeQuery()) {
              while (tagRows.next()) tags.add(tagRows.getString(1));
            }
          }
          return new AdvancedListing(
              result.getString(1), result.getString(2), result.getString(3), result.getBoolean(4),
              result.getString(5), result.getString(6), nullableLong(result, 7),
              nullableLong(result, 8), nullableLong(result, 9), nullableLong(result, 10),
              result.getLong(11), result.getString(12), List.copyOf(tags));
        }
      }
  }

  public long currentListingPrice(long listingId) {
    return database.withConnection(connection -> {
      Listing listing = readListing(connection, listingId);
      AuctionDetails auction = readAuctionDetails(connection, listingId);
      return isDutchAuction(auction) ? dutchPrice(auction, Instant.now()) : listing.price();
    });
  }

  public AuctionInsights auctionInsights(long listingId, long viewerUserId, int requestedLimit) {
    int limit = Math.max(1, Math.min(requestedLimit, 100));
    return database.withConnection(
        connection -> {
          AuctionDetails details = readAuctionDetails(connection, listingId);
          boolean sealed = isVickreyAuction(details);
          int bidCount;
          int participantCount;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT COUNT(*),COUNT(DISTINCT bidder_user_id) FROM market_bids"
                      + " WHERE listing_id=?")) {
            statement.setLong(1, listingId);
            try (ResultSet result = statement.executeQuery()) {
              result.next();
              bidCount = result.getInt(1);
              participantCount = result.getInt(2);
            }
          }
          Long myBid = null;
          String myStatus = "NONE";
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT bid_amount,status FROM market_bids WHERE listing_id=?"
                      + " AND bidder_user_id=? ORDER BY id DESC LIMIT 1")) {
            statement.setLong(1, listingId);
            statement.setLong(2, viewerUserId);
            try (ResultSet result = statement.executeQuery()) {
              if (result.next()) {
                myBid = result.getLong(1);
                myStatus = result.getString(2);
              }
            }
          }
          List<AuctionBidPoint> points = new ArrayList<>();
          if (!sealed) {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "SELECT b.bid_amount,u.username,b.created_at FROM market_bids b"
                        + " JOIN web_users u ON u.id=b.bidder_user_id"
                        + " WHERE b.listing_id=? ORDER BY b.id DESC LIMIT ?")) {
              statement.setLong(1, listingId);
              statement.setInt(2, limit);
              try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                  points.add(
                      new AuctionBidPoint(
                          result.getLong(1), result.getString(2), instant(result, 3)));
                }
              }
            }
          }
          return new AuctionInsights(
              details.algorithm(),
              bidCount,
              participantCount,
              sealed,
              myBid,
              myStatus,
              List.copyOf(points));
        });
  }

  public AuctionBidResult placeAuctionBid(
      long bidderUserId,
      UUID bidderUuid,
      long listingId,
      long amount,
      String idempotencyKey) {
    requireKey(idempotencyKey);
    if (amount < 1) throw new ServiceException("invalid_bid", "Bid amount is invalid");
    return database.inTransaction(
        connection -> {
          AuctionBidResult prior = priorAuctionBid(connection, bidderUserId, idempotencyKey);
          if (prior != null) {
            if (prior.listingId() != listingId) {
              throw new ServiceException("idempotency_conflict", "Bid key belongs to another auction");
            }
            return prior;
          }
          Listing listing = readListing(connection, listingId);
          AuctionDetails auction = readAuctionDetails(connection, listingId);
          if (!"AUCTION".equals(auction.tradeMode())
              || !"ACTIVE".equals(listing.status())
              || auction.endAt() == null
              || !auction.endAt().isAfter(Instant.now())) {
            throw new ServiceException("auction_unavailable", "Auction is unavailable");
          }
          if (listing.sellerUserId() == bidderUserId) {
            throw new ServiceException("self_trade", "Seller cannot bid on the same auction");
          }
          if (isDutchAuction(auction)) {
            throw new ServiceException("auction_only_buy", "Dutch auctions must be bought directly");
          }
          boolean sealed = isVickreyAuction(auction);
          long minimum =
              sealed || auction.highestBid() == null
                  ? auction.startPrice()
                  : Math.addExact(auction.highestBid(), auction.minIncrement());
          if (amount < minimum) {
            throw new ServiceException("bid_too_low", "Bid amount is below the current minimum");
          }
          long frozenDelta = amount;
          if (!sealed && auction.highestBidderUserId() != null
              && auction.highestBidderUserId() == bidderUserId) {
            frozenDelta = amount - auction.highestBid();
          }
          wallets.applyDelta(
              connection,
              bidderUserId,
              listing.currency(),
              -frozenDelta,
              "AUCTION_BID_FREEZE",
              "auction-bid:" + bidderUserId + ":" + idempotencyKey,
              true);
          if (!sealed && auction.highestBidderUserId() != null
              && auction.highestBidderUserId() != bidderUserId) {
            wallets.applyDelta(
                connection,
                auction.highestBidderUserId(),
                listing.currency(),
                auction.highestBid(),
                "AUCTION_OUTBID_REFUND",
                "auction-outbid:" + auction.highestBidId(),
                false);
          }
          long bidId;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO market_bids"
                      + " (listing_id,bidder_user_id,bidder_uuid,bid_amount,status,idempotency_key)"
                      + " VALUES (?,?,?,?,?,?)",
                  Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, listingId);
            statement.setLong(2, bidderUserId);
            statement.setString(3, bidderUuid.toString());
            statement.setLong(4, amount);
            statement.setString(5, sealed ? "SEALED" : "LEADING");
            statement.setString(6, idempotencyKey);
            statement.executeUpdate();
            bidId = generatedId(statement);
          }
          if (!sealed && auction.highestBidId() != null) {
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "UPDATE market_bids SET status=?,outbid_at=CURRENT_TIMESTAMP,refunded_at=?"
                        + " WHERE id=? AND status='LEADING'")) {
              boolean sameBidder = auction.highestBidderUserId() == bidderUserId;
              statement.setString(1, sameBidder ? "SUPERSEDED" : "OUTBID");
              statement.setObject(2, sameBidder ? null : Timestamp.from(Instant.now()));
              statement.setLong(3, auction.highestBidId());
              if (statement.executeUpdate() != 1) {
                throw new ServiceException("auction_conflict", "Auction changed concurrently");
              }
            }
          }
          if (sealed) {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE market_listings SET auction_last_bid_at=CURRENT_TIMESTAMP"
                    + " WHERE id=? AND status='ACTIVE'")) {
              statement.setLong(1, listingId);
              if (statement.executeUpdate() != 1) {
                throw new ServiceException("auction_conflict", "Auction changed concurrently");
              }
            }
          } else {
            String expectedClause =
                auction.highestBidId() == null
                    ? "auction_highest_bid_id IS NULL"
                    : "auction_highest_bid_id=?";
            try (PreparedStatement statement =
                connection.prepareStatement(
                    "UPDATE market_listings SET auction_highest_bid=?,"
                        + "auction_highest_bidder_user_id=?,auction_highest_bidder_uuid=?,"
                        + "auction_highest_bid_id=?,auction_last_bid_at=CURRENT_TIMESTAMP"
                        + " WHERE id=? AND status='ACTIVE' AND "
                        + expectedClause)) {
              statement.setLong(1, amount);
              statement.setLong(2, bidderUserId);
              statement.setString(3, bidderUuid.toString());
              statement.setLong(4, bidId);
              statement.setLong(5, listingId);
              if (auction.highestBidId() != null) statement.setLong(6, auction.highestBidId());
              if (statement.executeUpdate() != 1) {
                throw new ServiceException("auction_conflict", "Auction changed concurrently");
              }
            }
            extendEnglishAuctionIfNeeded(connection, listingId, auction);
          }
          return new AuctionBidResult(
              bidId, listingId, amount, minimum, sealed ? "SEALED" : "LEADING", idempotencyKey);
        });
  }

  public int settleExpiredAuctions(int requestedLimit) {
    int limit = Math.max(1, Math.min(requestedLimit, 100));
    List<Long> due =
        database.withConnection(
            connection -> {
              try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT id FROM market_listings WHERE trade_mode='AUCTION'"
                          + " AND status='ACTIVE' AND auction_end_at<=CURRENT_TIMESTAMP"
                          + " ORDER BY auction_end_at,id LIMIT ?")) {
                statement.setInt(1, limit);
                try (ResultSet result = statement.executeQuery()) {
                  List<Long> ids = new ArrayList<>();
                  while (result.next()) ids.add(result.getLong(1));
                  return ids;
                }
              }
            });
    int settled = 0;
    for (long listingId : due) {
      if (settleAuction(listingId)) settled++;
    }
    return settled;
  }

  public Purchase placeProductInCheckout(
      Connection connection, PurchaseRequest request, long frozenTotal) throws SQLException {
    if (request.quantity() < 1 || frozenTotal < 0) {
      throw new ServiceException("invalid_checkout", "Checkout product input is invalid");
    }
    requireKey(request.idempotencyKey());
    Purchase existing = findPurchase(connection, request.userId(), request.idempotencyKey());
    if (existing != null) return existing;
    Product product = readProduct(connection, request.productId());
    if (!product.active()) throw new ServiceException("product_inactive", "Product is inactive");
    if (product.kind() == ProductKind.GROUP_BUY_VOUCHER && request.quantity() != 1) {
      throw new ServiceException(
          "invalid_quantity", "Group-buy vouchers must be purchased one at a time");
    }
    if (product.stockRemaining() != null && product.stockRemaining() < request.quantity()) {
      throw new ServiceException("insufficient_stock", "Product stock is insufficient");
    }
    String orderNo =
        "CHK-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    long orderId;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO orders (order_no,user_id,mc_uuid,currency,total_amount,status,"
                + "idempotency_key,target_server_id,claim_token) VALUES (?,?,?,?,?,?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, orderNo);
      statement.setLong(2, request.userId());
      statement.setString(3, request.playerId().toString());
      statement.setString(4, product.currency().name());
      statement.setLong(5, frozenTotal);
      statement.setString(
          6, product.kind() == ProductKind.GROUP_BUY_VOUCHER ? "DELIVERED" : "PAID");
      statement.setString(7, request.idempotencyKey());
      statement.setString(8, request.targetServerId());
      statement.setString(9, UUID.randomUUID().toString());
      statement.executeUpdate();
      orderId = generatedId(statement);
    }
    long itemId;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO order_items (order_id,product_id,quantity,unit_price) VALUES (?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, orderId);
      statement.setLong(2, product.id());
      statement.setInt(3, request.quantity());
      statement.setLong(4, request.quantity() == 0 ? 0 : frozenTotal / request.quantity());
      statement.executeUpdate();
      itemId = generatedId(statement);
    }
    if (product.kind() == ProductKind.GROUP_BUY_VOUCHER) {
      insertGroupBuyVoucher(connection, orderId, request.userId(), product.id());
    } else {
      insertProductDelivery(connection, request, product, orderId, itemId);
    }
    if (product.stockRemaining() != null) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE products SET stock_remaining=stock_remaining-?,updated_at=CURRENT_TIMESTAMP "
                  + "WHERE id=? AND stock_remaining>=?")) {
        statement.setInt(1, request.quantity());
        statement.setLong(2, product.id());
        statement.setInt(3, request.quantity());
        if (statement.executeUpdate() != 1) {
          throw new ServiceException("stock_conflict", "Product stock changed concurrently");
        }
      }
    }
    return new Purchase(
        orderId,
        orderNo,
        request.userId(),
        product.id(),
        request.quantity(),
        product.currency(),
        frozenTotal,
        product.kind() == ProductKind.GROUP_BUY_VOUCHER ? "DELIVERED" : "PAID",
        request.idempotencyKey());
  }

  public MarketTrade placeMarketInCheckout(
      Connection connection,
      MarketBuyRequest request,
      long buyerTotal,
      long sellerReceive,
      long feeAmount,
      long taxAmount)
      throws SQLException {
    requireKey(request.idempotencyKey());
    MarketTrade prior =
        findMarketTrade(connection, request.buyerUserId(), request.idempotencyKey());
    if (prior != null) return prior;
    Listing listing = readListing(connection, request.listingId());
    if (!listing.status().equals("ACTIVE") || listing.quantity() < request.quantity()) {
      throw new ServiceException("listing_unavailable", "Listing is unavailable");
    }
    if (listing.sellerUserId() == request.buyerUserId()) {
      throw new ServiceException("self_trade", "Seller cannot buy the same listing");
    }
    long baseTotal = Math.multiplyExact(listing.price(), request.quantity());
    String biz = "CHECKOUT-MARKET-" + UUID.randomUUID();
    if (sellerReceive > 0) {
      wallets.applyDelta(
          connection,
          listing.sellerUserId(),
          listing.currency(),
          sellerReceive,
          "MARKET_SELL",
          biz + ":seller",
          false);
    }
    int remaining = listing.quantity() - request.quantity();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE market_listings SET quantity=?,escrow_remaining=?,buyer_user_id=?,buyer_uuid=?,"
                + "status=CASE WHEN source_mode='SUPPLY' AND ?=0 THEN 'SUPPLY_EMPTY' "
                + "WHEN ?=0 THEN 'SOLD' ELSE 'ACTIVE' END,"
                + "sold_at=CASE WHEN ?=0 AND source_mode<>'SUPPLY' "
                + "THEN CURRENT_TIMESTAMP ELSE sold_at END,"
                + "supply_sold_total=supply_sold_total+CASE WHEN source_mode='SUPPLY' THEN ? ELSE 0 END "
                + "WHERE id=? AND status='ACTIVE' AND quantity=?")) {
      statement.setInt(1, remaining);
      statement.setInt(2, remaining);
      statement.setLong(3, request.buyerUserId());
      statement.setString(4, request.buyerId().toString());
      statement.setInt(5, remaining);
      statement.setInt(6, remaining);
      statement.setInt(7, remaining);
      statement.setInt(8, request.quantity());
      statement.setLong(9, listing.id());
      statement.setInt(10, listing.quantity());
      if (statement.executeUpdate() != 1) {
        throw new ServiceException("listing_conflict", "Listing changed concurrently");
      }
    }
    long tradeId;
    String claimToken = UUID.randomUUID().toString();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO market_trades"
                + " (listing_id,buyer_user_id,seller_user_id,currency,unit_price,"
                + "quantity,total_price,buyer_total,seller_receive,fee_amount,tax_amount,idempotency_key,claim_token,status)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'PAID')",
            Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, listing.id());
      statement.setLong(2, request.buyerUserId());
      statement.setLong(3, listing.sellerUserId());
      statement.setString(4, listing.currency().name());
      statement.setLong(5, listing.price());
      statement.setInt(6, request.quantity());
      statement.setLong(7, baseTotal);
      statement.setLong(8, buyerTotal);
      statement.setLong(9, sellerReceive);
      statement.setLong(10, feeAmount);
      statement.setLong(11, taxAmount);
      statement.setString(12, request.idempotencyKey());
      statement.setString(13, claimToken);
      statement.executeUpdate();
      tradeId = generatedId(statement);
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO market_item_deliveries (listing_id,trade_id,target_user_id,target_uuid,"
                + "target_server_id,item_blob,quantity,delivery_type,next_retry_at) "
                + "VALUES (?,?,?,?,?,?,?,'BUYER_ITEM',CURRENT_TIMESTAMP)")) {
      statement.setLong(1, listing.id());
      statement.setLong(2, tradeId);
      statement.setLong(3, request.buyerUserId());
      statement.setString(4, request.buyerId().toString());
      statement.setString(5, request.targetServerId());
      statement.setBytes(6, envelopes.encode(listing.item()));
      statement.setInt(7, request.quantity());
      statement.executeUpdate();
    }
    return new MarketTrade(
        tradeId,
        listing.id(),
        request.buyerUserId(),
        listing.sellerUserId(),
        request.quantity(),
        listing.currency(),
        buyerTotal,
        request.idempotencyKey(),
        "PAID");
  }

  public void registerPaymentProvider(PaymentProvider provider) {
    Objects.requireNonNull(provider, "provider");
    String id = provider.id().trim().toLowerCase(Locale.ROOT);
    if (id.isEmpty() || paymentProviders.putIfAbsent(id, provider) != null) {
      throw new IllegalArgumentException("duplicate or blank payment provider id");
    }
  }

  public java.util.Optional<PaymentProviderConfiguration> paymentProviderConfiguration(
      String providerId, String locale) {
    PaymentProvider provider = requirePaymentProvider(providerId);
    return java.util.Optional.ofNullable(provider.configuration(locale));
  }

  public PaymentConfigUpdateResult updatePaymentProviderConfiguration(
      String providerId, PaymentConfigUpdateRequest request) {
    Objects.requireNonNull(request, "request");
    PaymentConfigUpdateResult result = requirePaymentProvider(providerId).updateConfiguration(request);
    if (result == null) {
      throw new ServiceException(
          "payment_config_update_failed", "Payment provider returned no result");
    }
    return result;
  }

  private PaymentProvider requirePaymentProvider(String providerId) {
    if (providerId == null || providerId.isBlank()) {
      throw new ServiceException(
          "payment_provider_required", "A payment provider must be specified");
    }
    PaymentProvider provider = paymentProviders.get(providerId.trim().toLowerCase(Locale.ROOT));
    if (provider == null) {
      throw new ServiceException(
          "payment_provider_not_found", "Payment provider was not found: " + providerId);
    }
    return provider;
  }

  public Recharge createRecharge(RechargeRequest request) {
    requireKey(request.idempotencyKey());
    if (request.amountMinor() < 1 || request.coinAmount() < 1) {
      throw new ServiceException("invalid_amount", "Recharge amount is invalid");
    }
    PaymentProvider provider = paymentProviders.get(request.providerId().toLowerCase(Locale.ROOT));
    if (provider == null)
      throw new ServiceException("payment_unavailable", "Payment provider is unavailable");
    Recharge existing = recharge(request.idempotencyKey());
    if (existing != null) return existing;
    String orderId = "R-" + UUID.randomUUID().toString().replace("-", "");
    database.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO webshopx_recharge_order"
                      + " (order_id,user_id,player_uuid,amount_minor,currency,coin_amount,status,provider,metadata)"
                      + " VALUES (?,?,?,?,?,?,'CREATED',?,?)")) {
            statement.setString(1, orderId);
            statement.setLong(2, request.userId());
            statement.setString(
                3, request.playerId() == null ? null : request.playerId().toString());
            statement.setLong(4, request.amountMinor());
            statement.setString(5, request.currency().toUpperCase(Locale.ROOT));
            statement.setLong(6, request.coinAmount());
            statement.setString(7, provider.id());
            statement.setString(
                8, "{\"idempotencyKey\":\"" + json(request.idempotencyKey()) + "\"}");
            statement.executeUpdate();
          }
          return null;
        });
    PaymentSession session;
    try {
      session =
          provider.create(
              orderId, request.amountMinor(), request.currency(), request.description());
    } catch (RuntimeException failure) {
      updateRechargeFailure(orderId, "provider_error", failure.getMessage());
      throw failure;
    }
    database.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE webshopx_recharge_order SET"
                      + " status='PAYING',provider_order_id=?,pay_url=?,qr_code_url=?,expire_time=?,updated_at=CURRENT_TIMESTAMP"
                      + " WHERE order_id=? AND status='CREATED'")) {
            statement.setString(1, session.providerOrderId());
            statement.setString(2, session.payUrl());
            statement.setString(3, session.qrCodeUrl());
            statement.setTimestamp(
                4, session.expiresAt() == null ? null : Timestamp.from(session.expiresAt()));
            statement.setString(5, orderId);
            if (statement.executeUpdate() != 1)
              throw new ServiceException("recharge_conflict", "Recharge changed");
          }
          return null;
        });
    return rechargeByOrder(orderId);
  }

  public Recharge applyPayment(PaymentNotification notification) {
    return database.inTransaction(
        connection -> {
          Recharge current = rechargeByOrder(connection, notification.orderId());
          if (current.status().equals("CREDITED")) return current;
          if (!current.providerId().equalsIgnoreCase(notification.providerId())
              || current.amountMinor() != notification.amountMinor()
              || !current.currency().equalsIgnoreCase(notification.currency())) {
            throw new ServiceException(
                "payment_mismatch", "Payment notification does not match the order");
          }
          if (!notification.paid())
            throw new ServiceException("payment_not_paid", "Payment is not paid");
          wallets.applyDelta(
              connection,
              current.userId(),
              CurrencyType.SHOP_COIN,
              current.coinAmount(),
              "RECHARGE_PAYMENT",
              current.orderId(),
              false);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE webshopx_recharge_order SET"
                      + " status='CREDITED',provider_order_id=?,paid_time=?,credited_time=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP"
                      + " WHERE order_id=? AND status IN ('CREATED','PAYING','PAID')")) {
            statement.setString(1, notification.providerOrderId());
            statement.setTimestamp(2, Timestamp.from(notification.paidAt()));
            statement.setString(3, current.orderId());
            if (statement.executeUpdate() != 1)
              throw new ServiceException("recharge_conflict", "Recharge changed");
          }
          return rechargeByOrder(connection, current.orderId());
        });
  }

  private Product readProduct(Connection connection, long id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,sku,title,remark,currency,price,product_type,command_template,item_material,"
                + "stock_remaining,per_user_limit,active FROM products WHERE id=?")) {
      statement.setLong(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next())
          throw new ServiceException("product_not_found", "Product was not found");
        return product(result);
      }
    }
  }

  private void lockProductForSnapshot(Connection connection, long productId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM products WHERE id=?" + database.sqlProvider().forUpdateClause())) {
      statement.setLong(1, productId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new ServiceException("product_not_found", "Product was not found");
        }
      }
    }
  }

  private void reserveProductUserLimit(
      Connection connection, Product product, long userId, int quantity) throws SQLException {
    if (product.perUserLimit() == null) return;
    try (PreparedStatement statement =
        connection.prepareStatement(database.sqlProvider().upsertProductUserUsageSql())) {
      statement.setLong(1, product.id());
      statement.setLong(2, userId);
      statement.setInt(3, quantity);
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT used_count FROM product_user_usage WHERE product_id=? AND user_id=?")) {
      statement.setLong(1, product.id());
      statement.setLong(2, userId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || result.getInt(1) > product.perUserLimit()) {
          throw new ServiceException("product_limit_reached", "Product purchase limit reached");
        }
      }
    }
  }

  private Product readProductBySku(Connection connection, String sku) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,sku,title,remark,currency,price,product_type,command_template,item_material,"
                + "stock_remaining,per_user_limit,active FROM products WHERE sku=?")) {
      statement.setString(1, sku);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new ServiceException("product_not_found", "Product was not found");
        }
        return product(result);
      }
    }
  }

  private static Product product(ResultSet result) throws SQLException {
    Object stock = result.getObject("stock_remaining");
    return new Product(
        result.getLong("id"),
        result.getString("sku"),
        result.getString("title"),
        result.getString("remark"),
        CurrencyType.valueOf(result.getString("currency")),
        result.getLong("price"),
        ProductKind.valueOf(result.getString("product_type")),
        result.getString("command_template"),
        result.getString("item_material"),
        stock == null ? null : ((Number) stock).intValue(),
        nullableInt(result, "per_user_limit"),
        result.getBoolean("active"));
  }

  private static Integer nullableInt(ResultSet result, String column) throws SQLException {
    Object value = result.getObject(column);
    return value == null ? null : ((Number) value).intValue();
  }

  private static void setNullableInt(PreparedStatement statement, int index, Integer value)
      throws SQLException {
    if (value == null) statement.setObject(index, null);
    else statement.setInt(index, value);
  }

  private Purchase findPurchase(Connection connection, long userId, String key)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT"
                + " o.id,o.order_no,o.user_id,oi.product_id,oi.quantity,o.currency,o.total_amount,o.status,o.idempotency_key"
                + " FROM orders o JOIN order_items oi ON oi.order_id=o.id WHERE o.user_id=? AND"
                + " o.idempotency_key=?")) {
      statement.setLong(1, userId);
      statement.setString(2, key);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? purchase(result) : null;
      }
    }
  }

  private static Purchase purchase(ResultSet result) throws SQLException {
    return new Purchase(
        result.getLong(1),
        result.getString(2),
        result.getLong(3),
        result.getLong(4),
        result.getInt(5),
        CurrencyType.valueOf(result.getString(6)),
        result.getLong(7),
        result.getString(8),
        result.getString(9));
  }

  private void insertProductDelivery(
      Connection connection,
      PurchaseRequest request,
      Product product,
      long orderId,
      long itemId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO delivery_queue"
                + " (order_id,item_id,mc_uuid,target_server_id,command_text,delivery_kind,"
                + "payload_json,quantity,next_retry_at)"
                + " VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)")) {
      statement.setLong(1, orderId);
      statement.setLong(2, itemId);
      statement.setString(3, request.playerId().toString());
      statement.setString(4, request.targetServerId());
      statement.setString(5, product.commandTemplate());
      statement.setString(6, product.kind().name());
      statement.setString(7, productDeliveryPayload(connection, product));
      statement.setInt(8, request.quantity());
      statement.executeUpdate();
    }
  }

  private String productDeliveryPayload(Connection connection, Product product) throws SQLException {
    if (product.kind() == ProductKind.SNAPSHOT_ITEM) {
      ItemEnvelope template = officialSnapshotEnvelope(connection, product.id());
      return "{\"registryId\":\""
          + json(template.registryId())
          + "\",\"envelopeBase64\":\""
          + Base64.getEncoder().encodeToString(envelopes.encode(template))
          + "\"}";
    }
    return product.registryId() == null
        ? null
        : "{\"registryId\":\"" + json(product.registryId()) + "\"}";
  }

  private ItemEnvelope officialSnapshotEnvelope(Connection connection, long productId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT s.item_blob FROM products p JOIN official_item_snapshots s"
            + " ON s.id=p.snapshot_id WHERE p.id=? AND p.snapshot_id IS NOT NULL")) {
      statement.setLong(1, productId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new ServiceException("invalid_snapshot", "Product snapshot is missing");
        }
        try {
          return envelopes.decode(result.getBytes(1));
        } catch (IllegalArgumentException failure) {
          throw new ServiceException("invalid_snapshot", "Product snapshot is corrupt");
        }
      }
    }
  }

  private long findOrCreateOfficialSnapshot(Connection connection, ItemEnvelope template)
      throws SQLException {
    byte[] encoded = envelopes.encode(template);
    String snapshotHash = snapshotStorageHash(template);
    Long existing = findOfficialSnapshot(connection, snapshotHash);
    if (existing != null) return existing;
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO official_item_snapshots"
            + " (item_hash,item_blob,item_meta_json,item_material) VALUES (?,?,?,?)",
        Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, snapshotHash);
      statement.setBytes(2, encoded);
      statement.setString(3, CommerceJson.create().toJson(template.summary()));
      statement.setString(4, template.registryId());
      statement.executeUpdate();
      return generatedId(statement);
    } catch (SQLException failure) {
      Long concurrent = findOfficialSnapshot(connection, snapshotHash);
      if (concurrent != null) return concurrent;
      throw failure;
    }
  }

  private Long findOfficialSnapshot(Connection connection, String hash)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT id,item_blob FROM official_item_snapshots WHERE item_hash=?")) {
      statement.setString(1, hash);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return null;
        ItemEnvelope stored;
        try {
          stored = envelopes.decode(result.getBytes(2));
        } catch (IllegalArgumentException failure) {
          throw new ServiceException("invalid_snapshot", "Stored snapshot is corrupt");
        }
        if (!hash.equals(snapshotStorageHash(stored))
            || !stored.payloadHash().equals(
                com.webshopx.core.ItemEnvelopeService.sha256(stored.payload()))) {
          throw new ServiceException(
              "snapshot_hash_collision", "Snapshot hash resolves to different item data");
        }
        return result.getLong(1);
      }
    }
  }

  private void insertSnapshotVersion(
      Connection connection, long productId, long snapshotId, String hash, long createdBy)
      throws SQLException {
    int version;
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT COALESCE(MAX(version),0)+1 FROM product_item_snapshots WHERE product_id=?")) {
      statement.setLong(1, productId);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        version = result.getInt(1);
      }
    }
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO product_item_snapshots"
            + " (product_id,snapshot_id,version,item_hash,created_by) VALUES (?,?,?,?,?)")) {
      statement.setLong(1, productId);
      statement.setLong(2, snapshotId);
      statement.setInt(3, version);
      statement.setString(4, hash);
      statement.setLong(5, createdBy);
      statement.executeUpdate();
    }
  }

  private static ItemEnvelope normalizedTemplate(ItemEnvelope template) {
    Objects.requireNonNull(template, "template");
    return template.count() == 1
        ? template
        : new ItemEnvelope(
            template.schemaVersion(),
            template.codec(),
            template.codecVersion(),
            template.compatibilityDomain(),
            template.registryId(),
            1,
            template.payloadEncoding(),
            template.payload(),
            template.payloadHash(),
            template.summary(),
            template.createdAt());
  }

  private String snapshotStorageHash(ItemEnvelope template) {
    var domain = template.compatibilityDomain();
    String identity = String.join(
        "\n",
        template.payloadHash(),
        template.codec(),
        Integer.toString(template.codecVersion()),
        domain.platform(),
        domain.loader(),
        domain.minecraftVersion(),
        Integer.toString(domain.itemCodecVersion()),
        domain.modpackFingerprint(),
        template.registryId());
    return com.webshopx.core.ItemEnvelopeService.sha256(
        identity.getBytes(StandardCharsets.UTF_8));
  }

  private void insertGroupBuyVoucher(
      Connection connection, long orderId, long userId, long productId) throws SQLException {
    String code =
        "GB-"
            + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase(Locale.ROOT);
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO group_buy_vouchers"
                + " (code,order_id,user_id,product_id,status) VALUES (?,?,?,?,'ISSUED')")) {
      statement.setString(1, code);
      statement.setLong(2, orderId);
      statement.setLong(3, userId);
      statement.setLong(4, productId);
      statement.executeUpdate();
    }
  }

  private GroupBuyVoucher readGroupBuyVoucher(
      Connection connection, Long userId, Long orderId, String code, boolean lock)
      throws SQLException {
    String predicate = code != null ? "gv.code=?" : "gv.user_id=? AND gv.order_id=?";
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT gv.id,gv.code,gv.status,gv.consumed_at,o.order_no,gv.user_id,"
                + "u.username,p.sku,p.title FROM group_buy_vouchers gv"
                + " JOIN orders o ON o.id=gv.order_id"
                + " JOIN web_users u ON u.id=gv.user_id"
                + " JOIN products p ON p.id=gv.product_id WHERE "
                + predicate
                + (lock ? database.sqlProvider().forUpdateClause() : ""))) {
      if (code != null) {
        statement.setString(1, code);
      } else {
        statement.setLong(1, userId);
        statement.setLong(2, orderId);
      }
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return null;
        return new GroupBuyVoucher(
            result.getLong(1),
            result.getString(2),
            result.getString(3),
            result.getString(5),
            result.getLong(6),
            result.getString(7),
            result.getString(8),
            result.getString(9),
            instant(result, 4));
      }
    }
  }

  private String groupBuyVoucherStatus(Connection connection, long orderId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT status FROM group_buy_vouchers WHERE order_id=?")) {
      statement.setLong(1, orderId);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getString(1).toUpperCase(Locale.ROOT) : null;
      }
    }
  }

  private static void markGroupBuyVoucherRefunded(Connection connection, long orderId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE group_buy_vouchers SET status='REFUNDED'"
                + " WHERE order_id=? AND status='ISSUED'")) {
      statement.setLong(1, orderId);
      statement.executeUpdate();
    }
  }

  private static String normalizeGroupBuyVoucherCode(String rawCode) {
    if (rawCode == null || rawCode.isBlank()) {
      throw new ServiceException("invalid_voucher", "Group-buy voucher code is required");
    }
    String code = rawCode.trim().toUpperCase(Locale.ROOT);
    if (!code.matches("GB-[A-Z0-9]{8,32}")) {
      throw new ServiceException("invalid_voucher", "Group-buy voucher code format is invalid");
    }
    return code;
  }

  private Listing readListing(Connection connection, long id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT"
                + " id,seller_user_id,seller_uuid,currency,price,quantity,raw_item_blob,remark,status,market_side,escrow_total,escrow_remaining"
                + " FROM market_listings WHERE id=?")) {
      statement.setLong(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next())
          throw new ServiceException("listing_not_found", "Listing was not found");
        return new Listing(
            result.getLong(1),
            result.getLong(2),
            UUID.fromString(result.getString(3)),
            CurrencyType.valueOf(result.getString(4)),
            result.getLong(5),
            result.getInt(6),
            envelopes.decode(result.getBytes(7)),
            result.getString(8),
            result.getString(9),
            result.getString(10),
            result.getLong(11),
            result.getLong(12));
      }
    }
  }

  private AuctionDetails readAuctionDetails(Connection connection, long listingId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT trade_mode,auction_algorithm,auction_start_price,auction_min_increment,"
                + "auction_started_at,auction_public_end_at,auction_end_at,auction_params_json,"
                + "auction_highest_bid,auction_highest_bidder_user_id,auction_highest_bidder_uuid,"
                + "auction_highest_bid_id,auction_last_bid_at"
                + " FROM market_listings WHERE id=?")) {
      statement.setLong(1, listingId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new ServiceException("listing_not_found", "Listing was not found");
        }
        return new AuctionDetails(
            listingId,
            result.getString(1),
            result.getString(2),
            nullableLong(result, 3),
            nullableLong(result, 4),
            instant(result, 5),
            instant(result, 6),
            instant(result, 7),
            result.getString(8),
            nullableLong(result, 9),
            nullableLong(result, 10),
            result.getString(11) == null ? null : UUID.fromString(result.getString(11)),
            nullableLong(result, 12),
            instant(result, 13));
      }
    }
  }

  private AuctionBidResult priorAuctionBid(
      Connection connection, long bidderUserId, String idempotencyKey) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,listing_id,bid_amount,status,idempotency_key FROM market_bids"
                + " WHERE bidder_user_id=? AND idempotency_key=?")) {
      statement.setLong(1, bidderUserId);
      statement.setString(2, idempotencyKey);
      try (ResultSet result = statement.executeQuery()) {
        return result.next()
            ? new AuctionBidResult(
                result.getLong(1),
                result.getLong(2),
                result.getLong(3),
                result.getLong(3),
                result.getString(4),
                result.getString(5))
            : null;
      }
    }
  }

  private boolean settleAuction(long listingId) {
    return database.inTransaction(
        connection -> {
          Listing listing = readListing(connection, listingId);
          AuctionDetails auction = readAuctionDetails(connection, listingId);
          if (!"ACTIVE".equals(listing.status())
              || !"AUCTION".equals(auction.tradeMode())
              || auction.endAt() == null
              || auction.endAt().isAfter(Instant.now())) {
            return false;
          }
          List<AuctionBid> bids = pendingAuctionBids(connection, listingId);
          boolean vickrey = isVickreyAuction(auction);
          AuctionBid winner = vickrey
              ? (bids.isEmpty() ? null : bids.get(0))
              : ascendingWinner(auction);
          JsonObject params = MarketAlgorithmRegistry.parseParams(auction.paramsJson());
          long reserve = Math.max(0L, MarketAlgorithmRegistry.getLongParam(params, "reservePrice", 0L));
          if (winner == null || (reserve > 0 && winner.amount() < reserve)) {
            refundAuctionBids(connection, listing, bids, null, "auction-no-winner");
            return returnExpiredAuction(connection, listing);
          }
          long finalPrice = winner.amount();
          if (vickrey) {
            long secondPrice = bids.size() > 1 ? bids.get(1).amount() : auction.startPrice();
            finalPrice = Math.min(winner.amount(), Math.max(Math.max(auction.startPrice(), secondPrice), reserve));
          }
          String settlementKey = "auction-settle:" + listingId;
          wallets.applyDelta(
              connection,
              listing.sellerUserId(),
              listing.currency(),
              finalPrice,
              "AUCTION_SETTLEMENT",
              settlementKey,
              false);
          if (vickrey && winner.amount() > finalPrice) {
            wallets.applyDelta(
                connection,
                winner.userId(),
                listing.currency(),
                winner.amount() - finalPrice,
                "AUCTION_VICKREY_REFUND",
                settlementKey + ":winner-refund",
                false);
          }
          refundAuctionBids(connection, listing, bids, winner.id(), "auction-settle");
          long tradeId;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO market_trades"
                      + " (listing_id,buyer_user_id,seller_user_id,currency,unit_price,quantity,"
                      + "total_price,buyer_total,seller_receive,idempotency_key,claim_token,status,settled_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,'SETTLED',CURRENT_TIMESTAMP)",
                  Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, listingId);
            statement.setLong(2, winner.userId());
            statement.setLong(3, listing.sellerUserId());
            statement.setString(4, listing.currency().name());
            statement.setLong(5, finalPrice);
            statement.setInt(6, listing.quantity());
            statement.setLong(7, finalPrice);
            statement.setLong(8, finalPrice);
            statement.setLong(9, finalPrice);
            statement.setString(10, settlementKey);
            statement.setString(11, UUID.randomUUID().toString());
            statement.executeUpdate();
            tradeId = generatedId(statement);
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE market_listings SET status='SOLD',buyer_user_id=?,buyer_uuid=?,"
                      + "quantity=0,escrow_remaining=0,sold_at=CURRENT_TIMESTAMP"
                      + " WHERE id=? AND status='ACTIVE'")) {
            statement.setLong(1, winner.userId());
            statement.setString(2, winner.uuid().toString());
            statement.setLong(3, listingId);
            if (statement.executeUpdate() != 1) {
              throw new ServiceException("auction_conflict", "Auction changed concurrently");
            }
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE market_bids SET status='SETTLED',settled_at=CURRENT_TIMESTAMP"
                      + " WHERE id=? AND status IN ('LEADING','SEALED')")) {
            statement.setLong(1, winner.id());
            if (statement.executeUpdate() != 1) {
              throw new ServiceException("auction_conflict", "Auction bid changed concurrently");
            }
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO market_item_deliveries"
                      + " (listing_id,trade_id,target_user_id,target_uuid,target_server_id,item_blob,quantity,delivery_type,next_retry_at)"
                      + " VALUES (?,?,?,?,NULL,?,?,'AUCTION_WIN',CURRENT_TIMESTAMP)")) {
            statement.setLong(1, listingId);
            statement.setLong(2, tradeId);
            statement.setLong(3, winner.userId());
            statement.setString(4, winner.uuid().toString());
            statement.setBytes(5, envelopes.encode(listing.item()));
            statement.setInt(6, listing.quantity());
            statement.executeUpdate();
          }
          return true;
        });
  }

  private static boolean isDutchAuction(AuctionDetails auction) {
    return "AUCTION".equals(auction.tradeMode())
        && "DUTCH_AUCTION_V1".equals(auction.algorithm());
  }

  private static boolean isVickreyAuction(AuctionDetails auction) {
    return "AUCTION".equals(auction.tradeMode())
        && "VICKREY_AUCTION_V1".equals(auction.algorithm());
  }

  private static long dutchPrice(AuctionDetails auction, Instant now) {
    if (!isDutchAuction(auction) || auction.startedAt() == null || auction.endAt() == null) {
      throw new ServiceException("auction_unavailable", "Dutch auction is unavailable");
    }
    long floor = Math.max(1L, MarketAlgorithmRegistry.getLongParam(
        MarketAlgorithmRegistry.parseParams(auction.paramsJson()), "floorPrice", 1L));
    return MarketAlgorithmRegistry.computeDutchPrice(
        auction.startPrice(),
        floor,
        LocalDateTime.ofInstant(auction.startedAt(), ZoneOffset.UTC),
        LocalDateTime.ofInstant(auction.endAt(), ZoneOffset.UTC),
        LocalDateTime.ofInstant(now, ZoneOffset.UTC));
  }

  private static boolean hasPendingAuctionBids(Connection connection, long listingId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT 1 FROM market_bids WHERE listing_id=?"
            + " AND status IN ('LEADING','SEALED') AND refunded_at IS NULL LIMIT 1")) {
      statement.setLong(1, listingId);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static List<AuctionBid> pendingAuctionBids(Connection connection, long listingId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT id,bidder_user_id,bidder_uuid,bid_amount FROM market_bids"
            + " WHERE listing_id=? AND status IN ('LEADING','SEALED')"
            + " AND refunded_at IS NULL AND settled_at IS NULL"
            + " ORDER BY bid_amount DESC,created_at ASC,id ASC")) {
      statement.setLong(1, listingId);
      try (ResultSet result = statement.executeQuery()) {
        List<AuctionBid> bids = new ArrayList<>();
        while (result.next()) {
          bids.add(new AuctionBid(
              result.getLong(1), result.getLong(2), UUID.fromString(result.getString(3)),
              result.getLong(4)));
        }
        return bids;
      }
    }
  }

  private static AuctionBid ascendingWinner(AuctionDetails auction) {
    if (auction.highestBidId() == null || auction.highestBidderUserId() == null
        || auction.highestBidderUuid() == null || auction.highestBid() == null) return null;
    return new AuctionBid(
        auction.highestBidId(), auction.highestBidderUserId(), auction.highestBidderUuid(),
        auction.highestBid());
  }

  private void refundAuctionBids(
      Connection connection, Listing listing, List<AuctionBid> bids, Long winnerId, String reason)
      throws SQLException {
    for (AuctionBid bid : bids) {
      if (winnerId != null && bid.id() == winnerId) continue;
      wallets.applyDelta(
          connection, bid.userId(), listing.currency(), bid.amount(), "AUCTION_BID_REFUND",
          reason + ":" + listing.id() + ":" + bid.id(), false);
      try (PreparedStatement statement = connection.prepareStatement(
          "UPDATE market_bids SET status='REFUNDED',refunded_at=CURRENT_TIMESTAMP"
              + " WHERE id=? AND status IN ('LEADING','SEALED') AND refunded_at IS NULL")) {
        statement.setLong(1, bid.id());
        if (statement.executeUpdate() != 1) {
          throw new ServiceException("auction_conflict", "Auction bid changed concurrently");
        }
      }
    }
  }

  private boolean returnExpiredAuction(Connection connection, Listing listing) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "UPDATE market_listings SET status='UNLISTED',unlisted_at=CURRENT_TIMESTAMP"
            + " WHERE id=? AND status='ACTIVE'")) {
      statement.setLong(1, listing.id());
      if (statement.executeUpdate() != 1) return false;
    }
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO market_item_deliveries"
            + " (listing_id,trade_id,target_user_id,target_uuid,target_server_id,item_blob,quantity,delivery_type,next_retry_at)"
            + " VALUES (?,NULL,?,?,NULL,?,?,'AUCTION_EXPIRED',CURRENT_TIMESTAMP)")) {
      statement.setLong(1, listing.id());
      statement.setLong(2, listing.sellerUserId());
      statement.setString(3, listing.sellerId().toString());
      statement.setBytes(4, envelopes.encode(listing.item()));
      statement.setInt(5, listing.quantity());
      statement.executeUpdate();
    }
    return true;
  }

  private void extendEnglishAuctionIfNeeded(
      Connection connection, long listingId, AuctionDetails auction) throws SQLException {
    if (!"ENGLISH_AUCTION_V1".equals(auction.algorithm()) || auction.publicEndAt() == null) return;
    JsonObject params = MarketAlgorithmRegistry.parseParams(auction.paramsJson());
    long window = Math.max(0L, MarketAlgorithmRegistry.getLongParam(
        params, "antiSnipingWindowSeconds", 30L));
    long extension = Math.max(0L, MarketAlgorithmRegistry.getLongParam(
        params, "antiSnipingExtendSeconds", 30L));
    if (extension == 0L || auction.publicEndAt().isAfter(Instant.now().plusSeconds(window))) return;
    Instant nextPublicEnd = auction.publicEndAt().plusSeconds(extension);
    Instant nextActualEnd = auction.endAt().plusSeconds(extension);
    try (PreparedStatement statement = connection.prepareStatement(
        "UPDATE market_listings SET auction_public_end_at=?,auction_end_at=?"
            + " WHERE id=? AND status='ACTIVE' AND auction_end_at=?")) {
      statement.setTimestamp(1, Timestamp.from(nextPublicEnd));
      statement.setTimestamp(2, Timestamp.from(nextActualEnd));
      statement.setLong(3, listingId);
      statement.setTimestamp(4, Timestamp.from(auction.endAt()));
      statement.executeUpdate();
    }
  }

  private record AuctionBid(long id, long userId, UUID uuid, long amount) {}
  private record SupplySettings(
      String sourceMode, Integer batchSize, Integer maxStock, boolean accessProtected) {}

  private static Long nullableLong(ResultSet result, int column) throws SQLException {
    long value = result.getLong(column);
    return result.wasNull() ? null : value;
  }

  private MarketTrade findMarketTrade(Connection connection, long buyer, String key)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT"
                + " id,listing_id,buyer_user_id,seller_user_id,quantity,currency,total_price,idempotency_key,status"
                + " FROM market_trades WHERE buyer_user_id=? AND idempotency_key=?")) {
      statement.setLong(1, buyer);
      statement.setString(2, key);
      try (ResultSet result = statement.executeQuery()) {
        return result.next()
            ? new MarketTrade(
                result.getLong(1),
                result.getLong(2),
                result.getLong(3),
                result.getLong(4),
                result.getInt(5),
                CurrencyType.valueOf(result.getString(6)),
                result.getLong(7),
                result.getString(8),
                result.getString(9))
            : null;
      }
    }
  }

  private Recharge recharge(String idempotencyKey) {
    return database.withConnection(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT order_id FROM webshopx_recharge_order WHERE metadata LIKE ? ORDER BY id"
                      + " DESC LIMIT 1")) {
            statement.setString(1, "%\"idempotencyKey\":\"" + idempotencyKey + "\"%");
            try (ResultSet result = statement.executeQuery()) {
              return result.next() ? rechargeByOrder(connection, result.getString(1)) : null;
            }
          }
        });
  }

  public Recharge rechargeByOrder(String orderId) {
    return database.withConnection(connection -> rechargeByOrder(connection, orderId));
  }

  public Recharge rechargeForUser(long userId, String orderId) {
    Recharge recharge = rechargeByOrder(orderId);
    if (recharge.userId() != userId) {
      throw new ServiceException("recharge_forbidden", "Recharge belongs to another user");
    }
    return recharge;
  }

  public Recharge reconcileRecharge(long userId, String orderId) {
    Recharge current = rechargeForUser(userId, orderId);
    if ("CREDITED".equals(current.status()) || "CANCELLED".equals(current.status())) {
      return current;
    }
    PaymentProvider provider = paymentProviders.get(current.providerId().toLowerCase(Locale.ROOT));
    if (provider == null) {
      throw new ServiceException("payment_unavailable", "Payment provider is unavailable");
    }
    PaymentNotification notification = provider.query(current);
    if (notification == null || !notification.paid()) return current;
    return applyPayment(notification);
  }

  public Recharge cancelRecharge(long userId, String orderId) {
    return database.inTransaction(
        connection -> {
          Recharge recharge = rechargeByOrder(connection, orderId);
          if (recharge.userId() != userId) {
            throw new ServiceException("recharge_forbidden", "Recharge belongs to another user");
          }
          if (recharge.status().equals("CANCELLED")) return recharge;
          if (!recharge.status().equals("CREATED") && !recharge.status().equals("PAYING")) {
            throw new ServiceException(
                "recharge_not_cancellable", "Recharge can no longer be cancelled");
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE webshopx_recharge_order SET status='CANCELLED',"
                      + "updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND user_id=?"
                      + " AND status IN ('CREATED','PAYING')")) {
            statement.setString(1, orderId);
            statement.setLong(2, userId);
            if (statement.executeUpdate() != 1) {
              throw new ServiceException("recharge_conflict", "Recharge changed concurrently");
            }
          }
          return rechargeByOrder(connection, orderId);
        });
  }

  private static Recharge rechargeByOrder(Connection connection, String orderId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT"
                + " order_id,user_id,player_uuid,amount_minor,currency,coin_amount,status,provider,provider_order_id,pay_url,qr_code_url,expire_time"
                + " FROM webshopx_recharge_order WHERE order_id=?")) {
      statement.setString(1, orderId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next())
          throw new ServiceException("recharge_not_found", "Recharge was not found");
        Timestamp expires = result.getTimestamp(12);
        return new Recharge(
            result.getString(1),
            result.getLong(2),
            result.getString(3) == null ? null : UUID.fromString(result.getString(3)),
            result.getLong(4),
            result.getString(5),
            result.getLong(6),
            result.getString(7),
            result.getString(8),
            result.getString(9),
            result.getString(10),
            result.getString(11),
            expires == null ? null : expires.toInstant());
      }
    }
  }

  private void updateRechargeFailure(String orderId, String code, String message) {
    database.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE webshopx_recharge_order SET status='FAILED',error_code=?,error_message=?,"
                      + "updated_at=CURRENT_TIMESTAMP WHERE order_id=?")) {
            statement.setString(1, code);
            statement.setString(2, message);
            statement.setString(3, orderId);
            statement.executeUpdate();
          }
          return null;
        });
  }

  private static long generatedId(PreparedStatement statement) throws SQLException {
    try (ResultSet keys = statement.getGeneratedKeys()) {
      if (!keys.next()) throw new SQLException("generated key was not returned");
      return keys.getLong(1);
    }
  }

  private static Instant instant(ResultSet result, int column) throws SQLException {
    Timestamp value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static int deliveredQuantity(Connection connection, long orderId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT COALESCE(SUM(delivered_quantity),0) FROM delivery_queue WHERE order_id=?")) {
      statement.setLong(1, orderId);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getInt(1) : 0;
      }
    }
  }

  private static boolean hasProcessingDelivery(Connection connection, long orderId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM delivery_queue WHERE order_id=? AND status IN"
                + " ('PROCESSING','UNKNOWN')")) {
      statement.setLong(1, orderId);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static void cancelDeliveries(Connection connection, long orderId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE delivery_queue SET status='CANCELLED',last_error='order_cancelled'"
                + " WHERE order_id=? AND status IN ('PENDING','RETRY')")) {
      statement.setLong(1, orderId);
      statement.executeUpdate();
    }
  }

  private static void restoreProductStock(Connection connection, long orderId) throws SQLException {
    try (PreparedStatement items =
        connection.prepareStatement(
            "SELECT product_id,quantity FROM order_items WHERE order_id=?")) {
      items.setLong(1, orderId);
      try (ResultSet rows = items.executeQuery()) {
        while (rows.next()) {
          try (PreparedStatement update =
              connection.prepareStatement(
                  "UPDATE products SET"
                      + " stock_remaining=stock_remaining+?,updated_at=CURRENT_TIMESTAMP WHERE id=?"
                      + " AND stock_remaining IS NOT NULL")) {
            update.setInt(1, rows.getInt(2));
            update.setLong(2, rows.getLong(1));
            update.executeUpdate();
          }
        }
      }
    }
  }

  private static void insertRefundRequest(
      Connection connection, long userId, String orderNo, String idempotencyKey)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO refund_requests"
                + " (user_id,order_ref,idempotency_key,status) VALUES (?,?,?,'PROCESSING')")) {
      statement.setLong(1, userId);
      statement.setString(2, orderNo);
      statement.setString(3, idempotencyKey);
      statement.executeUpdate();
    }
  }

  private RefundResult priorRefund(
      Connection connection, long userId, String orderNo, String idempotencyKey)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT status,refund_amount,refund_quantity,error_code FROM refund_requests"
                + " WHERE user_id=? AND idempotency_key=?")) {
      statement.setLong(1, userId);
      statement.setString(2, idempotencyKey);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return null;
        if ("SUCCESS".equals(result.getString(1))) {
          return new RefundResult(orderNo, result.getLong(2), result.getInt(3), null);
        }
        if ("PROCESSING".equals(result.getString(1))) {
          throw new ServiceException(
              "refund_outcome_unknown", "Refund requires reconciliation before retry");
        }
        throw new ServiceException(
            result.getString(4) == null ? "refund_rejected" : result.getString(4),
            "Refund was rejected");
      }
    }
  }

  private static void validateProduct(ProductInput input) {
    Objects.requireNonNull(input, "input");
    if (input.sku() == null || !input.sku().trim().matches("[A-Za-z0-9_.-]{1,64}")) {
      throw new ServiceException("invalid_product", "SKU is invalid");
    }
    if (input.title() == null
        || input.title().isBlank()
        || input.title().length() > 128
        || input.price() < 0
        || input.currency() == null
        || input.kind() == null) {
      throw new ServiceException("invalid_product", "Product fields are invalid");
    }
    if ((input.kind() == ProductKind.GIVE_ITEM || input.kind() == ProductKind.SNAPSHOT_ITEM
            || isRecycleProduct(input.kind()))
        && (input.registryId() == null
            || !input.registryId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))) {
      throw new ServiceException("invalid_product", "Registry id is invalid");
    }
    if (input.stock() != null && input.stock() < 1) {
      throw new ServiceException("invalid_product", "Stock is invalid");
    }
    if (input.perUserLimit() != null && input.perUserLimit() < 1) {
      throw new ServiceException("invalid_product", "Per-user limit is invalid");
    }
  }

  private static void requireKey(String key) {
    if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) {
      throw new ServiceException("invalid_idempotency_key", "Idempotency key is invalid");
    }
  }

  private static boolean isRecycleProduct(ProductKind kind) {
    return kind == ProductKind.RECYCLE_ITEM
        || kind == ProductKind.RECYCLE_COMMAND_ITEM
        || kind == ProductKind.RECYCLE_CUSTOM_ITEM;
  }

  private static void requireManagedAssetPath(String path, String prefix) {
    if (path == null || !path.startsWith(prefix) || path.contains("..") || path.contains("\\")) {
      throw new ServiceException("invalid_asset_path", "Asset path is invalid");
    }
  }

  private static String json(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  public enum ProductKind {
    COMMAND,
    GIVE_ITEM,
    RECYCLE_ITEM,
    RECYCLE_COMMAND_ITEM,
    RECYCLE_CUSTOM_ITEM,
    GROUP_BUY_VOUCHER,
    SNAPSHOT_ITEM
  }

  public record ProductInput(
      String sku,
      String title,
      String remark,
      CurrencyType currency,
      long price,
      ProductKind kind,
      String commandTemplate,
      String registryId,
      Integer stock,
      Integer perUserLimit,
      boolean active) {
    public ProductInput(
        String sku,
        String title,
        String remark,
        CurrencyType currency,
        long price,
        ProductKind kind,
        String commandTemplate,
        String registryId,
        Integer stock,
        boolean active) {
      this(sku, title, remark, currency, price, kind, commandTemplate, registryId, stock, null,
          active);
    }
  }

  public record Product(
      long id,
      String sku,
      String title,
      String remark,
      CurrencyType currency,
      long price,
      ProductKind kind,
      String commandTemplate,
      String registryId,
      Integer stockRemaining,
      Integer perUserLimit,
      boolean active) {}

  public record ProductQuote(
      long productId,
      long firstUnitPrice,
      long lastUnitPrice,
      long averageUnitPrice,
      long nextUnitPrice,
      int quantity,
      long totalAmount,
      long currentDemandScore,
      long nextDemandScore) {}

  public record OfficialRecycleMatch(
      long productId,
      String sku,
      String title,
      CurrencyType currency,
      long unitPrice,
      int quotedQuantity,
      long totalAmount,
      int remaining) {}

  public record OfficialRecycleResult(
      long orderId,
      String orderNo,
      long productId,
      CurrencyType currency,
      long unitPrice,
      int quantity,
      long totalAmount) {}

  public record ProductPricePoint(long orderItemId, long price, int quantity, Instant createdAt) {}

  public record AssetPathUpdate(long id, String previousPath, String currentPath) {}

  public record PurchaseRequest(
      long userId,
      UUID playerId,
      long productId,
      int quantity,
      String idempotencyKey,
      String targetServerId) {}

  public record Purchase(
      long id,
      String orderNo,
      long userId,
      long productId,
      int quantity,
      CurrencyType currency,
      long total,
      String status,
      String idempotencyKey) {}

  public record GroupBuyVoucher(
      long id,
      String code,
      String status,
      String orderNo,
      long userId,
      String username,
      String productSku,
      String productTitle,
      Instant consumedAt) {}

  public record SnapshotVersion(
      long id,
      long snapshotId,
      int version,
      String itemHash,
      Long createdBy,
      Instant activeFrom,
      String itemMaterial,
      String itemMetaJson,
      boolean active) {}

  public record Delivery(
      long id,
      long orderId,
      ProductKind kind,
      String command,
      String payloadJson,
      int quantity,
      int deliveredQuantity,
      String status) {}

  public record OrderView(
      long id,
      String orderNo,
      String status,
      String currency,
      long totalAmount,
      String playerUuid,
      Instant createdAt,
      Instant deliveredAt,
      Instant refundedAt,
      Instant refundDeadline,
      long refundAmount,
      int refundQuantity,
      String claimToken,
      int quantity,
      long unitPrice,
      String sku,
      String productTitle,
      String productRemark,
      String productType,
      String itemMaterial,
      int deliveredQuantity,
      boolean canRefund,
      int refundableQuantity,
      String groupBuyVoucherCode,
      String groupBuyVoucherStatus,
      Instant groupBuyVoucherConsumedAt) {}

  public record AdminOrderView(
      OrderView order, long userId, String username, UUID boundUuid) {}

  public record DeliveryTask(
      long id,
      String status,
      int retryCount,
      String lastError,
      Instant nextRetryAt,
      Instant deliveredAt,
      Instant claimedAt,
      Instant createdAt,
      int quantity,
      int deliveredQuantity,
      String deliveryKind,
      String targetServerId) {}

  public record DeliveryStatus(String orderNo, String status, List<DeliveryTask> deliveryTasks) {}

  public record MailboxEntry(
      String id,
      String type,
      String sourceType,
      String sourceRef,
      String title,
      String material,
      int quantity,
      int deliveredQuantity,
      int refundableQuantity,
      String status,
      Instant createdAt,
      boolean collectible,
      boolean refundable,
      String refundReason,
      String reason,
      String lastDeliveryError,
      Instant claimedAt,
      String itemFingerprint) {}

  public record RefundResult(
      String orderNo, long refundAmount, int refundQuantity, WalletService.WalletBalance balance) {}

  public record ListingRequest(
      long sellerUserId,
      UUID sellerId,
      CurrencyType currency,
      long price,
      int quantity,
      ItemEnvelope item,
      String remark) {}

  public record SupplyListingRequest(
      long ownerUserId,
      UUID ownerId,
      CurrencyType currency,
      long price,
      ItemEnvelope itemTemplate,
      String world,
      int x,
      int y,
      int z,
      int batchSize,
      int maxStock,
      boolean accessProtected,
      String idempotencyKey,
      String remark) {}

  public record SupplyListingReplayRequest(
      long ownerUserId,
      CurrencyType currency,
      long price,
      String expectedPayloadHash,
      String world,
      int x,
      int y,
      int z,
      int batchSize,
      int maxStock,
      boolean accessProtected,
      String idempotencyKey,
      String remark) {}

  public record SupplyProtection(
      long listingId, UUID ownerId, boolean accessProtected, String status) {}

  public record BuyListingRequest(
      long ownerUserId,
      UUID ownerId,
      CurrencyType currency,
      long price,
      int quantity,
      String itemMaterial,
      String idempotencyKey,
      String remark) {}

  public record AdvancedListingUpdate(
      long price,
      CurrencyType currency,
      String remark,
      Integer supplyBatchSize,
      Integer supplyMaxStock,
      Boolean supplyAccessProtected,
      List<String> tags,
      String displayNameOverride,
      String displayMaterial,
      String displayIconPath,
      String tradeMode,
      boolean dynamicPricingEnabled,
      String dynamicAlgorithm,
      String dynamicPricingMode,
      Long dynamicBasePrice,
      Long dynamicFloorPrice,
      Long dynamicCapPrice,
      Long dynamicPriceStep,
      String dynamicParamsJson,
      String auctionAlgorithm,
      Long auctionStartPrice,
      Long auctionMinIncrement,
      Instant auctionEndAt,
      String auctionParamsJson) {}

  public record AdvancedListing(
      String displayNameOverride,
      String displayMaterial,
      String displayIconPath,
      boolean dynamicPricingEnabled,
      String dynamicAlgorithm,
      String dynamicPricingMode,
      Long dynamicBasePrice,
      Long dynamicFloorPrice,
      Long dynamicCapPrice,
      Long dynamicPriceStep,
      long dynamicDemandScore,
      String dynamicParamsJson,
      List<String> tags) {}

  public record Listing(
      long id,
      long sellerUserId,
      UUID sellerId,
      CurrencyType currency,
      long price,
      int quantity,
      ItemEnvelope item,
      String remark,
      String status,
      String side,
      long escrowTotal,
      long escrowRemaining) {}

  public record AuctionDetails(
      long listingId,
      String tradeMode,
      String algorithm,
      Long startPrice,
      Long minIncrement,
      Instant startedAt,
      Instant publicEndAt,
      Instant endAt,
      String paramsJson,
      Long highestBid,
      Long highestBidderUserId,
      UUID highestBidderUuid,
      Long highestBidId,
      Instant lastBidAt) {}

  public record AuctionBidResult(
      long bidId,
      long listingId,
      long bidAmount,
      long minimumBid,
      String status,
      String idempotencyKey) {}

  public record AuctionInsights(
      String algorithm,
      int bidCount,
      int participantCount,
      boolean sealed,
      Long myBid,
      String myStatus,
      List<AuctionBidPoint> pricePoints) {}

  public record AuctionBidPoint(long amount, String bidderName, Instant createdAt) {}

  public record MarketBuyRequest(
      long buyerUserId,
      UUID buyerId,
      long listingId,
      int quantity,
      String idempotencyKey,
      String targetServerId,
      Long expectedUnitPrice,
      Long expectedBuyerTotal) {
    public MarketBuyRequest(
        long buyerUserId,
        UUID buyerId,
        long listingId,
        int quantity,
        String idempotencyKey,
        String targetServerId) {
      this(
          buyerUserId,
          buyerId,
          listingId,
          quantity,
          idempotencyKey,
          targetServerId,
          null,
          null);
    }
  }

  public record MarketFulfillRequest(
      long sellerUserId,
      UUID sellerId,
      long listingId,
      int quantity,
      String idempotencyKey,
      Long expectedUnitPrice,
      Long expectedBuyerTotal) {}

  public record MarketMatch(
      long listingId,
      long buyerUserId,
      CurrencyType currency,
      long unitPrice,
      int remaining,
      int quotedQuantity,
      long sellerReceive,
      long fee) {}

  public record MarketQuote(
      long listingId,
      CurrencyType currency,
      String side,
      long unitPrice,
      int quantity,
      long totalPrice,
      long buyerTotal,
      long sellerReceive,
      long feeAmount,
      long taxAmount,
      long firstUnitPrice,
      long lastUnitPrice,
      long averageUnitPrice,
      boolean dynamicPricingEnabled,
      String dynamicPricingMode,
      long currentDemandScore,
      long nextDemandScore,
      long nextUnitPrice) {}

  public record MarketPricePoint(long tradeId, long price, int quantity, Instant createdAt) {}

  public record MarketTrade(
      long id,
      long listingId,
      long buyerUserId,
      long sellerUserId,
      int quantity,
      CurrencyType currency,
      long total,
      String idempotencyKey,
      String status) {}

  public record RechargeRequest(
      long userId,
      UUID playerId,
      long amountMinor,
      String currency,
      long coinAmount,
      String providerId,
      String idempotencyKey,
      String description) {}

  public record Recharge(
      String orderId,
      long userId,
      UUID playerId,
      long amountMinor,
      String currency,
      long coinAmount,
      String status,
      String providerId,
      String providerOrderId,
      String payUrl,
      String qrCodeUrl,
      Instant expiresAt) {}

  public record PaymentSession(
      String providerOrderId, String payUrl, String qrCodeUrl, Instant expiresAt) {}

  public record PaymentNotification(
      String providerId,
      String providerOrderId,
      String orderId,
      long amountMinor,
      String currency,
      boolean paid,
      Instant paidAt) {}

  public record PaymentProviderConfiguration(
      String providerId,
      String displayName,
      PaymentConfigDescriptor descriptor,
      PaymentConfigSnapshot snapshot,
      java.util.Set<String> supportedLocales) {}

  public interface PaymentProvider {
    String id();

    PaymentSession create(String orderId, long amountMinor, String currency, String description);

    default PaymentNotification query(Recharge recharge) {
      return null;
    }

    default PaymentProviderConfiguration configuration(String locale) {
      return null;
    }

    default PaymentConfigUpdateResult updateConfiguration(PaymentConfigUpdateRequest request) {
      throw new ServiceException(
          "payment_config_unsupported", "Payment provider does not expose configuration");
    }
  }
}
