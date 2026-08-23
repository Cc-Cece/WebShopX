package com.webshopx;

import java.sql.Connection;
import java.sql.SQLException;

/** Platform adapter boundary used by the shared cart checkout coordinator. */
public interface CommerceCheckoutPort {
  OfficialQuote quoteOfficial(long userId, long productId, int quantity);

  MarketQuote quoteMarket(long userId, long listingId, int quantity);

  Placement placeOfficial(
      Connection connection,
      long userId,
      long productId,
      int quantity,
      String idempotencyKey,
      String deliveryMode,
      long buyerTotal)
      throws SQLException;

  Placement placeMarket(
      Connection connection,
      long userId,
      long listingId,
      int quantity,
      String idempotencyKey,
      String deliveryMode,
      FrozenMarketPricing pricing)
      throws SQLException;

  record OfficialQuote(
      String currency,
      long totalAmount,
      String sourceVersion,
      long firstUnitPrice,
      long lastUnitPrice,
      long averageUnitPrice) {}

  record MarketQuote(
      String currency,
      long totalAmount,
      long sellerUserId,
      String sourceVersion,
      long firstUnitPrice,
      long lastUnitPrice,
      long averageUnitPrice,
      long feeAmount,
      long taxAmount) {}

  record FrozenMarketPricing(long buyerTotal, long sellerReceive, long feeAmount, long taxAmount) {}

  record Placement(String legacyType, long legacyId) {}
}
