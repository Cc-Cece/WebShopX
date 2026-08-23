package com.webshopx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/** Safe seller templates; seller ownership and platform limits are server enforced. */
class SellerPromotionService {
  private final DatabaseManager databaseManager;
  private final PromotionService promotionService;

  SellerPromotionService(DatabaseManager databaseManager, PromotionService promotionService) {
    this.databaseManager = databaseManager;
    this.promotionService = promotionService;
  }

  PromotionService.PublishedRule create(long sellerId, SellerPromotionInput input) {
    Policy policy = databaseManager.withConnection(connection -> readPolicy(connection, sellerId));
    if (!policy.enabled())
      throw new ServiceException("seller_promotions_disabled", "Seller promotions are disabled");
    if (input.discountBps() > policy.maxDiscountBps())
      throw new ServiceException("seller_discount_limit", "Discount exceeds platform limit");
    if (input.endAt().isAfter(input.startAt().plus(policy.maxDurationDays(), ChronoUnit.DAYS)))
      throw new ServiceException(
          "seller_duration_limit", "Promotion duration exceeds platform limit");
    databaseManager.withConnection(
        connection -> {
          validateListings(connection, sellerId, input.listingIds());
          return null;
        });
    String code =
        "SELLER-" + sellerId + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    PromotionService.Campaign campaign =
        promotionService.createDraft(
            sellerId,
            new PromotionService.CampaignDraft(
                code,
                PromotionService.OwnerType.SELLER,
                sellerId,
                input.name(),
                null,
                input.startAt(),
                input.endAt()));
    Set<String> scope =
        input.listingIds().stream()
            .map(id -> "MARKET_LISTING:" + id)
            .collect(java.util.stream.Collectors.toSet());
    long discountAmount = input.template() == Template.AMOUNT_OFF ? input.discountAmount() : 0;
    int discountBps = input.template() == Template.PERCENT_OFF ? input.discountBps() : 0;
    PromotionService.RuleDraft rule =
        new PromotionService.RuleDraft(
            input.template() == Template.COUPON ? "SELLER_COUPON" : input.template().name(),
            "USER_PAYS",
            input.template() == Template.COUPON ? 5 : 3,
            input.template() == Template.COUPON ? "SELLER_COUPON" : "SELLER_PROMOTION",
            "SAME_SLOT_SINGLE",
            null,
            0,
            input.thresholdAmount() > 0 ? "AMOUNT" : "NONE",
            input.thresholdAmount(),
            0,
            3,
            "ONCE",
            1,
            discountAmount,
            discountBps,
            input.maxDiscountAmount(),
            "SELLER",
            0,
            null,
            false,
            scope,
            "{}",
            "{}",
            "{\"couponReturn\":\"FULL_REFUND_ONLY\"}",
            "{}");
    return promotionService.publish(sellerId, campaign.id(), campaign.version(), rule);
  }

  private Policy readPolicy(Connection c, long seller) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT"
                + " enabled,max_discount_bps,min_receivable_bps,max_active_campaigns,max_coupon_issue,max_duration_days,version"
                + " FROM seller_promotion_policies WHERE seller_user_id=?")) {
      s.setLong(1, seller);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) return new Policy(true, 5000, 1000, 20, 10000, 90, 0);
        return new Policy(
            r.getBoolean(1),
            r.getInt(2),
            r.getInt(3),
            r.getInt(4),
            r.getInt(5),
            r.getInt(6),
            r.getLong(7));
      }
    }
  }

  private void validateListings(Connection c, long seller, Set<Long> ids) throws SQLException {
    if (ids == null || ids.isEmpty())
      throw new ServiceException("seller_scope_required", "At least one listing is required");
    for (long id : ids) {
      try (PreparedStatement s =
          c.prepareStatement(
              "SELECT 1 FROM market_listings WHERE id=? AND seller_user_id=? AND market_side='SELL'"
                  + " AND trade_mode='DIRECT'")) {
        s.setLong(1, id);
        s.setLong(2, seller);
        try (ResultSet r = s.executeQuery()) {
          if (!r.next())
            throw new ServiceException(
                "seller_listing_forbidden", "Listing is not owned by seller");
        }
      }
    }
  }

  enum Template {
    AMOUNT_OFF,
    PERCENT_OFF,
    COUPON
  }

  record SellerPromotionInput(
      Template template,
      String name,
      Set<Long> listingIds,
      Instant startAt,
      Instant endAt,
      long thresholdAmount,
      long discountAmount,
      int discountBps,
      Long maxDiscountAmount,
      Long budgetAmount) {}

  record Policy(
      boolean enabled,
      int maxDiscountBps,
      int minReceivableBps,
      int maxActiveCampaigns,
      int maxCouponIssue,
      int maxDurationDays,
      long version) {}
}
