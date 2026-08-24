package com.webshopx;

import com.webshopx.core.ItemEnvelopeBinaryCodec;
import com.webshopx.platform.ItemEnvelope;
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

  public List<Listing> listings(boolean includeInactive) {
    return database.withConnection(
        connection -> {
          String sql =
              "SELECT id,seller_user_id,seller_uuid,currency,price,quantity,raw_item_blob,"
                  + "remark,status FROM market_listings"
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
                      result.getString(9)));
            }
            return List.copyOf(values);
          }
        });
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
          if (!listing.status().equals("ACTIVE") || listing.quantity() < request.quantity()) {
            throw new ServiceException("listing_unavailable", "Listing is unavailable");
          }
          if (listing.sellerUserId() == request.buyerUserId()) {
            throw new ServiceException("self_trade", "Seller cannot buy the same listing");
          }
          long total = Math.multiplyExact(listing.price(), request.quantity());
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
                + " id,seller_user_id,seller_uuid,currency,price,quantity,raw_item_blob,remark,status"
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
            result.getString(9));
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

  public record ListingRequest(
      long sellerUserId,
      UUID sellerId,
      CurrencyType currency,
      long price,
      int quantity,
      ItemEnvelope item,
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
      String status) {}

  public record MarketBuyRequest(
      long buyerUserId,
      UUID buyerId,
      long listingId,
      int quantity,
      String idempotencyKey,
      String targetServerId) {}

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
