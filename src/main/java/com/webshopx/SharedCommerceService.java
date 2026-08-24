package com.webshopx;

import com.webshopx.core.ItemEnvelopeBinaryCodec;
import com.webshopx.core.ItemEnvelopeService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Loader-neutral official-shop, market escrow, and recharge transaction graph. */
public final class SharedCommerceService {
  private final DatabaseManager database;
  private final WalletService wallets;
  private final ItemEnvelopeBinaryCodec envelopes = new ItemEnvelopeBinaryCodec();
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
                  + "command_template,item_material,item_amount,stock_remaining,active) "
                  + "VALUES (?,?,?,?,?,?,?,?,?,?,?)";
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
            statement.setBoolean(11, input.active());
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
                      + "active=?,updated_at=CURRENT_TIMESTAMP WHERE sku=?")) {
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
            statement.setBoolean(10, input.active());
            statement.setString(11, sku);
            if (statement.executeUpdate() == 1) return readProductBySku(connection, sku);
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO products (sku,title,remark,currency,price,product_type,"
                      + "command_template,item_material,item_amount,stock_remaining,active)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
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
            statement.setBoolean(11, input.active());
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

  public List<Product> products(boolean includeInactive) {
    return database.withConnection(
        connection -> {
          String sql =
              "SELECT id,sku,title,remark,currency,price,product_type,command_template,"
                  + "item_material,stock_remaining,active FROM products"
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
          if (product.stockRemaining() != null && product.stockRemaining() < request.quantity()) {
            throw new ServiceException("insufficient_stock", "Product stock is insufficient");
          }
          long total;
          try {
            total = Math.multiplyExact(product.price(), request.quantity());
          } catch (ArithmeticException overflow) {
            throw new ServiceException("invalid_amount", "Order total overflow");
          }
          String orderNo =
              "MOD-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
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
            statement.setString(6, "PAID");
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
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO delivery_queue"
                      + " (order_id,item_id,mc_uuid,target_server_id,command_text,delivery_kind,payload_json,quantity,next_retry_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)")) {
            statement.setLong(1, orderId);
            statement.setLong(2, itemId);
            statement.setString(3, request.playerId().toString());
            statement.setString(4, request.targetServerId());
            statement.setString(5, product.commandTemplate());
            statement.setString(6, product.kind().name());
            statement.setString(
                7,
                product.registryId() == null
                    ? null
                    : "{\"registryId\":\"" + json(product.registryId()) + "\"}");
            statement.setInt(8, request.quantity());
            statement.executeUpdate();
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
              "PAID",
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
                  + "p.remark,p.product_type,p.item_material FROM orders o JOIN order_items oi"
                  + " ON oi.order_id=o.id JOIN products p ON p.id=oi.product_id"
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
                        "PAID".equals(status) && delivered == 0,
                        Math.max(0, quantity - result.getInt(12))));
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
                      + "u.id,u.username,u.bound_uuid FROM orders o JOIN order_items oi"
                      + " ON oi.order_id=o.id JOIN products p ON p.id=oi.product_id"
                      + " JOIN web_users u ON u.id=o.user_id WHERE 1=1");
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
                        "PAID".equals(orderStatus) && delivered == 0,
                        Math.max(0, quantity - result.getInt(12)));
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
                        true,
                        false,
                        "PRODUCT_NOT_REFUNDABLE",
                        result.getString(7),
                        result.getString(9),
                        instant(result, 10),
                        item.payloadHash()));
              }
              return List.copyOf(values);
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
            try (ResultSet result = statement.executeQuery()) {
              return result.next() ? result.getInt(1) : 0;
            }
          }
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
                if (!"PAID".equals(status) || deliveredQuantity(connection, orderId) > 0) {
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
                            + " WHERE id=? AND user_id=? AND status='PAID'")) {
                  statement.setInt(1, quantity);
                  statement.setLong(2, total);
                  statement.setLong(3, orderId);
                  statement.setLong(4, userId);
                  if (statement.executeUpdate() != 1) {
                    throw new ServiceException("order_conflict", "Order state changed");
                  }
                }
                cancelDeliveries(connection, orderId);
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
          if (!listing.status().equals("ACTIVE") || listing.quantity() < quantity) {
            throw new ServiceException("listing_unavailable", "Listing is unavailable");
          }
          if (listing.sellerUserId() == buyerUserId) {
            throw new ServiceException("self_trade", "Seller cannot buy the same listing");
          }
          long total = Math.multiplyExact(listing.price(), quantity);
          return new MarketQuote(
              listing.id(),
              listing.currency(),
              listing.side(),
              listing.price(),
              quantity,
              total,
              total,
              total,
              0L,
              0L);
        });
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
    return updateListingSettings(sellerUserId, listingId, price, null, null, false);
  }

  public Listing updateListingSettings(
      long sellerUserId,
      long listingId,
      long price,
      CurrencyType currency,
      String remark) {
    return updateListingSettings(sellerUserId, listingId, price, currency, remark, true);
  }

  private Listing updateListingSettings(
      long sellerUserId,
      long listingId,
      long price,
      CurrencyType requestedCurrency,
      String remark,
      boolean updateRemark) {
    if (price < 1) throw new ServiceException("invalid_price", "Listing price is invalid");
    String normalizedRemark = remark == null || remark.isBlank() ? null : remark.trim();
    if (normalizedRemark != null && normalizedRemark.length() > 500) {
      throw new ServiceException("invalid_remark", "Listing remark is too long");
    }
    return database.inTransaction(
        connection -> {
          Listing listing = readOwnedListing(connection, sellerUserId, listingId);
          requireMutableListing(listing);
          CurrencyType nextCurrency =
              requestedCurrency == null ? listing.currency() : requestedCurrency;
          if (listing.side().equals("BUY") && nextCurrency != listing.currency()) {
            throw new ServiceException(
                "listing_currency_locked", "Buy listing currency cannot be changed");
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
                      + "escrow_total=escrow_total+?,escrow_remaining=?"
                      + " WHERE id=? AND seller_user_id=?"
                      + " AND status IN ('ACTIVE','PAUSED')")) {
            statement.setLong(1, price);
            statement.setString(2, nextCurrency.name());
            statement.setBoolean(3, updateRemark);
            statement.setString(4, normalizedRemark);
            statement.setLong(5, escrowDelta);
            statement.setLong(6, nextEscrow);
            statement.setLong(7, listingId);
            statement.setLong(8, sellerUserId);
            if (statement.executeUpdate() != 1) {
              throw new ServiceException("listing_conflict", "Listing changed concurrently");
            }
          }
          return readListing(connection, listingId);
        });
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

  public Listing unlist(long sellerUserId, long listingId) {
    return database.inTransaction(
        connection -> {
          Listing listing = readOwnedListing(connection, sellerUserId, listingId);
          if (listing.status().equals("UNLISTED")) return listing;
          requireMutableListing(listing);
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
          if (!listing.side().equals("SELL")) {
            throw new ServiceException("listing_side_invalid", "Listing is not a sell listing");
          }
          if (!listing.status().equals("ACTIVE") || listing.quantity() < request.quantity()) {
            throw new ServiceException("listing_unavailable", "Listing is unavailable");
          }
          if (listing.sellerUserId() == request.buyerUserId()) {
            throw new ServiceException("self_trade", "Seller cannot buy the same listing");
          }
          long total = Math.multiplyExact(listing.price(), request.quantity());
          if (request.expectedUnitPrice() != null
              && request.expectedUnitPrice().longValue() != listing.price()) {
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
                      + " WHEN ?=0 THEN CURRENT_TIMESTAMP ELSE sold_at END WHERE id=? AND"
                      + " status='ACTIVE' AND quantity=?")) {
            statement.setInt(1, remaining);
            statement.setInt(2, remaining);
            statement.setLong(3, request.buyerUserId());
            statement.setString(4, request.buyerId().toString());
            statement.setString(5, remaining == 0 ? "SOLD" : "ACTIVE");
            statement.setInt(6, remaining);
            statement.setLong(7, listing.id());
            statement.setInt(8, listing.quantity());
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
            statement.setLong(5, listing.price());
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
    if (product.stockRemaining() != null && product.stockRemaining() < request.quantity()) {
      throw new ServiceException("insufficient_stock", "Product stock is insufficient");
    }
    String orderNo =
        "CHK-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    long orderId;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO orders (order_no,user_id,mc_uuid,currency,total_amount,status,"
                + "idempotency_key,target_server_id,claim_token) VALUES (?,?,?,?,?,'PAID',?,?,?)",
            Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, orderNo);
      statement.setLong(2, request.userId());
      statement.setString(3, request.playerId().toString());
      statement.setString(4, product.currency().name());
      statement.setLong(5, frozenTotal);
      statement.setString(6, request.idempotencyKey());
      statement.setString(7, request.targetServerId());
      statement.setString(8, UUID.randomUUID().toString());
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
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO delivery_queue (order_id,item_id,mc_uuid,target_server_id,command_text,"
                + "delivery_kind,payload_json,quantity,next_retry_at) "
                + "VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)")) {
      statement.setLong(1, orderId);
      statement.setLong(2, itemId);
      statement.setString(3, request.playerId().toString());
      statement.setString(4, request.targetServerId());
      statement.setString(5, product.commandTemplate());
      statement.setString(6, product.kind().name());
      statement.setString(
          7,
          product.registryId() == null
              ? null
              : "{\"registryId\":\"" + json(product.registryId()) + "\"}");
      statement.setInt(8, request.quantity());
      statement.executeUpdate();
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
        "PAID",
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
                + "status=?,sold_at=CASE WHEN ?=0 THEN CURRENT_TIMESTAMP ELSE sold_at END "
                + "WHERE id=? AND status='ACTIVE' AND quantity=?")) {
      statement.setInt(1, remaining);
      statement.setInt(2, remaining);
      statement.setLong(3, request.buyerUserId());
      statement.setString(4, request.buyerId().toString());
      statement.setString(5, remaining == 0 ? "SOLD" : "ACTIVE");
      statement.setInt(6, remaining);
      statement.setLong(7, listing.id());
      statement.setInt(8, listing.quantity());
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
                + "stock_remaining,active FROM products WHERE id=?")) {
      statement.setLong(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next())
          throw new ServiceException("product_not_found", "Product was not found");
        return product(result);
      }
    }
  }

  private Product readProductBySku(Connection connection, String sku) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,sku,title,remark,currency,price,product_type,command_template,item_material,"
                + "stock_remaining,active FROM products WHERE sku=?")) {
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
        result.getBoolean("active"));
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
    if (input.kind() == ProductKind.GIVE_ITEM
        && (input.registryId() == null
            || !input.registryId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))) {
      throw new ServiceException("invalid_product", "Registry id is invalid");
    }
    if (input.stock() != null && input.stock() < 1) {
      throw new ServiceException("invalid_product", "Stock is invalid");
    }
  }

  private static void requireKey(String key) {
    if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) {
      throw new ServiceException("invalid_idempotency_key", "Idempotency key is invalid");
    }
  }

  private static String json(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  public enum ProductKind {
    COMMAND,
    GIVE_ITEM
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
      boolean active) {}

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

  public record ProductPricePoint(long orderItemId, long price, int quantity, Instant createdAt) {}

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
      int refundableQuantity) {}

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

  public record BuyListingRequest(
      long ownerUserId,
      UUID ownerId,
      CurrencyType currency,
      long price,
      int quantity,
      String itemMaterial,
      String idempotencyKey,
      String remark) {}

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
      long taxAmount) {}

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

  public interface PaymentProvider {
    String id();

    PaymentSession create(String orderId, long amountMinor, String currency, String description);
  }
}
