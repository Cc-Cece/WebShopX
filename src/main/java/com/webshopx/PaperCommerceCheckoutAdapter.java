package com.webshopx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Paper adapter for platform-specific official product and market placement. */
final class PaperCommerceCheckoutAdapter implements CommerceCheckoutPort {
  private final ProductService products;
  private final MarketService market;
  private final OrderService orders;
  private final DatabaseManager database;

  PaperCommerceCheckoutAdapter(
      DatabaseManager database,
      ProductService products,
      MarketService market,
      OrderService orders) {
    this.database = database;
    this.products = products;
    this.market = market;
    this.orders = orders;
  }

  @Override
  public OfficialQuote quoteOfficial(long userId, long productId, int quantity) {
    ProductService.ProductView product =
        products.listActiveProductsForUser(userId).stream()
            .filter(candidate -> candidate.id() == productId)
            .findFirst()
            .orElseThrow(
                () -> new ServiceException("SOURCE_NOT_CARTABLE", "Product is unavailable"));
    if (product.productSemantic() == ProductService.ProductSemantic.RECYCLE) {
      throw new ServiceException("SOURCE_NOT_CARTABLE", "Recycle products use recycle quote");
    }
    ProductService.ProductPriceQuote quote = products.quoteOrderPrice(product, quantity);
    return new OfficialQuote(
        product.currency().name(),
        quote.totalAmount(),
        String.valueOf(quote.currentDemandScore()),
        quote.firstUnitPrice(),
        quote.lastUnitPrice(),
        quote.averageUnitPrice());
  }

  @Override
  public MarketQuote quoteMarket(long userId, long listingId, int quantity) {
    MarketService.PurchaseQuote quote = market.quotePurchase(userId, listingId, quantity);
    return new MarketQuote(
        quote.currency().name(),
        quote.totalPrice(),
        sellerId(listingId),
        String.valueOf(quote.currentDemandScore()),
        quote.firstUnitPrice(),
        quote.lastUnitPrice(),
        quote.averageUnitPrice(),
        quote.feeAmount(),
        quote.taxAmount());
  }

  @Override
  public Placement placeOfficial(
      Connection connection,
      long userId,
      long productId,
      int quantity,
      String idempotencyKey,
      String deliveryMode,
      long buyerTotal)
      throws SQLException {
    OrderService.OrderPlacementResult result =
        orders.placeOrderInCheckout(
            connection, userId, productId, quantity, idempotencyKey, deliveryMode, buyerTotal);
    return new Placement("ORDER", orderId(connection, result.orderNo()));
  }

  @Override
  public Placement placeMarket(
      Connection connection,
      long userId,
      long listingId,
      int quantity,
      String idempotencyKey,
      String deliveryMode,
      FrozenMarketPricing pricing)
      throws SQLException {
    MarketService.TradeResult result =
        market.buyListingInCheckout(
            connection,
            userId,
            listingId,
            quantity,
            idempotencyKey,
            deliveryMode,
            new MarketService.FrozenMarketPricing(
                pricing.buyerTotal(),
                pricing.sellerReceive(),
                pricing.feeAmount(),
                pricing.taxAmount()));
    return new Placement("MARKET_TRADE", result.tradeId());
  }

  private long sellerId(long listingId) {
    return database.withConnection(connection -> sellerId(connection, listingId));
  }

  private static long sellerId(Connection connection, long listingId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT seller_user_id FROM market_listings WHERE id = ?")) {
      statement.setLong(1, listingId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new ServiceException("LISTING_UNAVAILABLE", "Listing is unavailable");
        }
        return result.getLong(1);
      }
    }
  }

  private static long orderId(Connection connection, String orderNo) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT id FROM orders WHERE order_no = ?")) {
      statement.setString(1, orderNo);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("Checkout order was not persisted");
        }
        return result.getLong(1);
      }
    }
  }
}
