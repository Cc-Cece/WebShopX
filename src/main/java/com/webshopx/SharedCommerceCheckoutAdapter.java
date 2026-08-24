package com.webshopx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Loader-neutral checkout adapter backed by the shared commerce transaction graph. */
public final class SharedCommerceCheckoutAdapter implements CommerceCheckoutPort {
  private final SharedCommerceService commerce;
  private final String serverId;

  public SharedCommerceCheckoutAdapter(SharedCommerceService commerce, String serverId) {
    this.commerce = Objects.requireNonNull(commerce, "commerce");
    this.serverId = Objects.requireNonNull(serverId, "serverId");
  }

  @Override
  public OfficialQuote quoteOfficial(long userId, long productId, int quantity) {
    if (quantity < 1) throw new ServiceException("invalid_quantity", "Quantity is invalid");
    SharedCommerceService.Product product = commerce.product(productId);
    if (!product.active()) throw new ServiceException("product_inactive", "Product is inactive");
    if (product.stockRemaining() != null && product.stockRemaining() < quantity) {
      throw new ServiceException("insufficient_stock", "Product stock is insufficient");
    }
    long total = Math.multiplyExact(product.price(), quantity);
    return new OfficialQuote(
        product.currency().name(),
        total,
        product.price() + ":" + product.stockRemaining(),
        product.price(),
        product.price(),
        product.price());
  }

  @Override
  public MarketQuote quoteMarket(long userId, long listingId, int quantity) {
    if (quantity < 1) throw new ServiceException("invalid_quantity", "Quantity is invalid");
    SharedCommerceService.Listing listing = commerce.listing(listingId);
    if (!listing.status().equals("ACTIVE") || listing.quantity() < quantity) {
      throw new ServiceException("listing_unavailable", "Listing is unavailable");
    }
    if (listing.sellerUserId() == userId) {
      throw new ServiceException("self_trade", "Seller cannot buy the same listing");
    }
    long total = Math.multiplyExact(listing.price(), quantity);
    return new MarketQuote(
        listing.currency().name(),
        total,
        listing.sellerUserId(),
        listing.price() + ":" + listing.quantity(),
        listing.price(),
        listing.price(),
        listing.price(),
        0,
        0);
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
    UUID playerId = boundUuid(connection, userId);
    SharedCommerceService.Purchase purchase =
        commerce.placeProductInCheckout(
            connection,
            new SharedCommerceService.PurchaseRequest(
                userId, playerId, productId, quantity, idempotencyKey, serverId),
            buyerTotal);
    return new Placement("ORDER", purchase.id());
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
    UUID playerId = boundUuid(connection, userId);
    SharedCommerceService.MarketTrade trade =
        commerce.placeMarketInCheckout(
            connection,
            new SharedCommerceService.MarketBuyRequest(
                userId, playerId, listingId, quantity, idempotencyKey, serverId),
            pricing.buyerTotal(),
            pricing.sellerReceive(),
            pricing.feeAmount(),
            pricing.taxAmount());
    return new Placement("MARKET_TRADE", trade.id());
  }

  private static UUID boundUuid(Connection connection, long userId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT bound_uuid FROM web_users WHERE id=?")) {
      statement.setLong(1, userId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || result.getString(1) == null) {
          throw new ServiceException("not_bound", "User is not bound");
        }
        return UUID.fromString(result.getString(1));
      }
    }
  }
}
