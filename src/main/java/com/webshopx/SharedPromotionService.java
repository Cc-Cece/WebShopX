package com.webshopx;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Platform-neutral use-case facade for cart, promotion, coupon and membership domains. */
public final class SharedPromotionService {
  private final CartService carts;
  private final PromotionService promotions;
  private final CouponService coupons;
  private final MembershipService memberships;
  private final SellerPromotionService sellerPromotions;
  private final MembershipCatalogService membershipCatalog;
  private final CouponCatalogService couponCatalog;
  private final MembershipCodeService membershipCodes;
  private final CheckoutQuoteService quotes;
  private final CheckoutService checkouts;
  private final CommerceRefundService refunds;

  public SharedPromotionService(DatabaseManager database) {
    this(database, null, null);
  }

  public SharedPromotionService(
      DatabaseManager database, WalletService wallets, CommerceCheckoutPort checkoutPort) {
    Objects.requireNonNull(database, "database");
    carts = new CartService(database);
    promotions = new PromotionService(database);
    coupons = new CouponService(database);
    memberships = new MembershipService(database);
    sellerPromotions = new SellerPromotionService(database, promotions);
    membershipCatalog = new MembershipCatalogService(database);
    couponCatalog = new CouponCatalogService(database);
    membershipCodes = new MembershipCodeService(database, memberships);
    if (wallets == null || checkoutPort == null) {
      quotes = null;
      checkouts = null;
      refunds = null;
    } else {
      quotes = new CheckoutQuoteService(database, carts, checkoutPort, promotions, memberships);
      checkouts =
          new CheckoutService(database, carts, quotes, coupons, wallets, checkoutPort, memberships);
      refunds = new CommerceRefundService(database, wallets, coupons);
    }
  }

  CartService cartService() {
    return carts;
  }

  PromotionService promotionService() {
    return promotions;
  }

  CouponService couponService() {
    return coupons;
  }

  MembershipService membershipService() {
    return memberships;
  }

  CheckoutQuoteService quoteService() {
    return required(quotes);
  }

  CheckoutService checkoutService() {
    return required(checkouts);
  }

  CommerceRefundService refundService() {
    return required(refunds);
  }

  public Object quote(long userId, CheckoutQuoteInput input) {
    return required(quotes)
        .quote(
            userId,
            new CheckoutQuoteService.QuoteCommand(
                input.cartVersion(),
                input.lineIds(),
                input.selectedRuleIds(),
                input.disabledRuleIds()));
  }

  public Object checkout(long userId, CheckoutSubmitInput input) {
    return required(checkouts)
        .submit(
            userId,
            new CheckoutService.SubmitCommand(
                input.quoteId(), input.cartVersion(), input.idempotencyKey()));
  }

  public Object refund(
      long userId, String checkoutNo, long lineId, int quantity, String idempotencyKey) {
    return required(refunds).refund(userId, checkoutNo, lineId, quantity, idempotencyKey);
  }

  private static <T> T required(T service) {
    if (service == null) {
      throw new ServiceException("capability_unavailable", "Checkout is not configured");
    }
    return service;
  }

  public Object cart(long userId) {
    return carts.get(userId);
  }

  public Object addCartLine(long userId, CartAdd input) {
    return carts.add(
        userId,
        new CartService.AddLine(
            CartService.SourceType.valueOf(input.sourceType()),
            input.sourceId(),
            input.quantity(),
            input.deliveryMode(),
            input.expectedVersion(),
            input.sourceVersion(),
            input.metadataJson()));
  }

  public Object updateCartLine(long userId, CartUpdate input) {
    return carts.update(
        userId,
        new CartService.UpdateLine(
            input.lineId(),
            input.quantity(),
            input.selected(),
            input.deliveryMode(),
            input.expectedVersion()));
  }

  public Object removeCartLine(long userId, long lineId, long expectedVersion) {
    return carts.remove(userId, lineId, expectedVersion);
  }

  public Object clearCart(long userId, long expectedVersion) {
    return carts.clear(userId, expectedVersion);
  }

  public Object coupons(long userId, String status) {
    return coupons.mine(userId, status);
  }

  public Object claimCoupon(long userId, long templateId, String requestId) {
    return coupons.claim(userId, templateId, requestId);
  }

  public Object memberships(long userId) {
    return memberships.list(userId);
  }

  public Object redeemMembership(long userId, String code, String requestId) {
    return membershipCodes.redeem(userId, code, requestId);
  }

  public Object sellerCampaigns(long sellerId) {
    return promotions.list("SELLER", sellerId);
  }

  public Object createSellerCampaign(long sellerId, SellerCampaignInput input) {
    return sellerPromotions.create(
        sellerId,
        new SellerPromotionService.SellerPromotionInput(
            SellerPromotionService.Template.valueOf(input.template()),
            input.name(),
            input.listingIds(),
            input.startAt(),
            input.endAt(),
            input.thresholdAmount(),
            input.discountAmount(),
            input.discountBps(),
            input.maxDiscountAmount(),
            input.budgetAmount()));
  }

  public Object transitionSellerCampaign(long sellerId, long campaignId, String action) {
    boolean owned =
        promotions.list("SELLER", sellerId).stream()
            .anyMatch(campaign -> campaign.id() == campaignId);
    if (!owned) {
      throw new ServiceException("promotion_forbidden", "Campaign is not owned by seller");
    }
    return promotions.transition(campaignId, action, sellerId);
  }

  public Object campaigns(String ownerType) {
    return promotions.list(ownerType, null);
  }

  public Object createCampaign(long actorId, CampaignInput input) {
    return promotions.createDraft(
        actorId,
        new PromotionService.CampaignDraft(
            input.code(),
            PromotionService.OwnerType.valueOf(input.ownerType()),
            input.ownerId(),
            input.name(),
            input.description(),
            input.startAt(),
            input.endAt()));
  }

  public Object publishCampaign(
      long actorId, long campaignId, long expectedVersion, PromotionRuleInput input) {
    return promotions.publish(
        actorId,
        campaignId,
        expectedVersion,
        new PromotionService.RuleDraft(
            input.ruleKind(),
            input.direction(),
            input.layer(),
            input.slot(),
            input.stackingPolicy(),
            input.exclusiveGroup(),
            input.priority(),
            input.thresholdType(),
            input.thresholdValue(),
            input.thresholdBasisLayer(),
            input.discountBasisLayer(),
            input.repeatMode(),
            input.maxRepeatCount(),
            input.discountAmount(),
            input.discountBps(),
            input.maxDiscountAmount(),
            input.fundingMode(),
            input.platformShareBps(),
            input.minPayableOverride(),
            input.allowZeroPayable(),
            input.lineIds(),
            input.eligibilityJson(),
            input.stackingJson(),
            input.refundPolicyJson(),
            input.displayJson()));
  }

  public Object transitionCampaign(long actorId, long campaignId, String action) {
    return promotions.transition(campaignId, action, actorId);
  }

  public EmergencyStop emergencyStop(long actorId) {
    int paused = 0;
    for (PromotionService.Campaign campaign : promotions.list(null, null)) {
      if ("ACTIVE".equals(campaign.status())) {
        promotions.transition(campaign.id(), "PAUSE", actorId);
        paused++;
      }
    }
    return new EmergencyStop(paused, Instant.now());
  }

  public Object grantCoupon(long actorId, long userId, long templateId, String idempotencyKey) {
    return coupons.grant(
        userId, templateId, "ADMIN", String.valueOf(actorId), idempotencyKey, actorId);
  }

  public Object couponTemplates() {
    return couponCatalog.list();
  }

  public Object createCouponTemplate(long actorId, CouponTemplateInput input) {
    return couponCatalog.create(
        actorId,
        new CouponCatalogService.TemplateDraft(
            input.code(),
            input.campaignId(),
            input.ruleVersionId(),
            input.ownerType(),
            input.ownerId(),
            input.name(),
            input.claimMode(),
            input.issueLimit(),
            input.perSubjectClaimLimit(),
            input.perSubjectUseLimit(),
            input.validityMode(),
            input.validFrom(),
            input.validUntil(),
            input.validDurationSeconds()));
  }

  public Object grantMembership(
      long actorId, long userId, long planVersionId, String idempotencyKey) {
    return memberships.grant(
        userId, planVersionId, "ADMIN", String.valueOf(actorId), idempotencyKey, actorId);
  }

  public Object revokeMembership(long actorId, long membershipId, String reason) {
    return memberships.revoke(membershipId, actorId, reason);
  }

  public Object membershipPlans() {
    return membershipCatalog.listPlans();
  }

  public Object createMembershipPlan(long actorId, MembershipPlanInput input) {
    return membershipCatalog.createPlan(
        actorId,
        new MembershipCatalogService.PlanDraft(input.code(), input.name(), input.description()));
  }

  public Object publishMembershipPlan(long actorId, long planId, MembershipVersionInput input) {
    return membershipCatalog.publishVersion(
        actorId,
        planId,
        new MembershipCatalogService.VersionDraft(
            input.levelCode(),
            input.levelRank(),
            input.durationMode(),
            input.durationValue(),
            input.renewalMode(),
            input.upgradePolicyJson(),
            input.refundPolicyJson(),
            input.benefitsJson()));
  }

  public void bindMembershipProduct(long actorId, long productId, long planVersionId) {
    membershipCatalog.bindProduct(actorId, productId, planVersionId);
  }

  public Object createMembershipCode(
      long actorId, long planVersionId, int maxUses, Instant validUntil) {
    return membershipCodes.create(actorId, planVersionId, maxUses, validUntil);
  }

  public record CartAdd(
      String sourceType,
      long sourceId,
      int quantity,
      String deliveryMode,
      long expectedVersion,
      String sourceVersion,
      String metadataJson) {}

  public record CartUpdate(
      long lineId, Integer quantity, Boolean selected, String deliveryMode, long expectedVersion) {}

  public record CheckoutQuoteInput(
      long cartVersion,
      List<Long> lineIds,
      Set<String> selectedRuleIds,
      Set<String> disabledRuleIds) {}

  public record CheckoutSubmitInput(String quoteId, long cartVersion, String idempotencyKey) {}

  public record SellerCampaignInput(
      String template,
      String name,
      Set<Long> listingIds,
      Instant startAt,
      Instant endAt,
      long thresholdAmount,
      long discountAmount,
      int discountBps,
      Long maxDiscountAmount,
      Long budgetAmount) {}

  public record CampaignInput(
      String code,
      String ownerType,
      Long ownerId,
      String name,
      String description,
      Instant startAt,
      Instant endAt) {}

  public record PromotionRuleInput(
      String ruleKind,
      String direction,
      int layer,
      String slot,
      String stackingPolicy,
      String exclusiveGroup,
      int priority,
      String thresholdType,
      long thresholdValue,
      int thresholdBasisLayer,
      int discountBasisLayer,
      String repeatMode,
      int maxRepeatCount,
      long discountAmount,
      int discountBps,
      Long maxDiscountAmount,
      String fundingMode,
      int platformShareBps,
      Long minPayableOverride,
      boolean allowZeroPayable,
      Set<String> lineIds,
      String eligibilityJson,
      String stackingJson,
      String refundPolicyJson,
      String displayJson) {}

  public record CouponTemplateInput(
      String code,
      long campaignId,
      long ruleVersionId,
      String ownerType,
      Long ownerId,
      String name,
      String claimMode,
      Long issueLimit,
      int perSubjectClaimLimit,
      int perSubjectUseLimit,
      String validityMode,
      Instant validFrom,
      Instant validUntil,
      Long validDurationSeconds) {}

  public record MembershipPlanInput(String code, String name, String description) {}

  public record MembershipVersionInput(
      String levelCode,
      int levelRank,
      String durationMode,
      Long durationValue,
      String renewalMode,
      String upgradePolicyJson,
      String refundPolicyJson,
      String benefitsJson) {}

  public record EmergencyStop(int pausedCampaigns, Instant stoppedAt) {}
}
