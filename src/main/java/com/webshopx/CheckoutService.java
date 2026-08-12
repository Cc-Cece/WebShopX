package com.webshopx;

import com.google.gson.Gson;
import com.webshopx.promotion.pricing.MathSupport;
import com.webshopx.promotion.pricing.PricingEngine;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Atomic parent checkout coordinator across official and market line adapters. */
class CheckoutService {
  private final DatabaseManager databaseManager;
  private final CartService cartService;
  private final CheckoutQuoteService quoteService;
  private final CouponService couponService;
  private final WalletService walletService;
  private final OrderService orderService;
  private final MarketService marketService;
  private final MembershipService membershipService;
  private final Gson gson = CommerceJson.create();

  CheckoutService(DatabaseManager databaseManager, CartService cartService,
      CheckoutQuoteService quoteService, CouponService couponService, WalletService walletService,
      OrderService orderService, MarketService marketService, MembershipService membershipService) {
    this.databaseManager = databaseManager; this.cartService = cartService;
    this.quoteService = quoteService; this.couponService = couponService;
    this.walletService = walletService; this.orderService = orderService;
    this.marketService = marketService; this.membershipService = membershipService;
  }

  CheckoutResult submit(long userId, SubmitCommand command) {
    if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
      throw new ServiceException("invalid_idempotency_key", "Idempotency key is required");
    }
    CheckoutQuoteService.Quote original = quoteService.read(userId, command.quoteId(), false);
    CheckoutQuoteService.Quote fresh = quoteService.quote(userId,
        new CheckoutQuoteService.QuoteCommand(command.cartVersion(),
            original.sources().stream().map(CheckoutQuoteService.SourceLine::cartLineId).toList(),
            original.selectedRuleIds(), original.disabledRuleIds()));
    if (!fresh.pricing().resultHash().equals(original.pricing().resultHash())
        || !fresh.inputHash().equals(original.inputHash())) {
      throw new ServiceException("PRICE_CHANGED", "Replacement quote: " + fresh.id());
    }
    return databaseManager.inTransaction(connection -> submitInTransaction(connection, userId, command, original));
  }

  private CheckoutResult submitInTransaction(Connection connection, long userId, SubmitCommand command,
      CheckoutQuoteService.Quote quote) throws SQLException {
    ExistingCheckout existing = findExisting(connection, userId, command.idempotencyKey());
    if (existing != null) {
      if (!existing.inputHash().equals(quote.inputHash())) throw new ServiceException("IDEMPOTENCY_CONFLICT", "Idempotency input differs");
      return readResult(connection, existing.id(), "EXISTING");
    }
    quoteService.read(connection, userId, command.quoteId(), true);
    CartService.CartView cart = cartService.readForCheckout(connection, userId, command.cartVersion());
    String checkoutNo = "CHK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    long checkoutId = insertCheckout(connection, checkoutNo, userId, quote, command.idempotencyKey());
    for (PricingEngine.Application application : quote.pricing().applications()) {
      if (application.userCouponId() != null) couponService.consume(connection, application.userCouponId(), userId, checkoutId);
    }
    for (PricingEngine.CurrencyTotal total : quote.pricing().currencyTotals().values()) {
      walletService.applyDelta(connection, userId, CurrencyType.valueOf(total.currency()),
          -total.payableAmount(), "CHECKOUT_DEBIT", checkoutNo + ":" + total.currency(), true);
    }

    Map<String, PricingEngine.LineResult> pricingById = new HashMap<>();
    quote.pricing().lines().forEach(line -> pricingById.put(line.id(), line));
    Map<GroupKey, GroupAccumulator> groups = new LinkedHashMap<>();
    for (CheckoutQuoteService.SourceLine source : quote.sources()) {
      PricingEngine.LineResult line = pricingById.get(source.pricingLineId());
      FundingAmounts funding = fundingForLine(quote.pricing().applications(), source.pricingLineId());
      long sellerDiscount = funding.seller(); long platformDiscount = funding.platform();
      long buyerTotal = Math.addExact(line.finalAmount(), source.taxAmount());
      long sellerReceive = source.sellerUserId() == null ? 0
          : Math.max(0, Math.subtractExact(Math.subtractExact(source.baseAmount(), sellerDiscount), source.feeAmount()));
      GroupKey key = new GroupKey(source.sourceType() == CartService.SourceType.OFFICIAL_PRODUCT
          ? "OFFICIAL_PURCHASE" : "MARKET_PURCHASE", source.currency(), source.sellerUserId(), source.deliveryMode());
      GroupAccumulator group = groups.computeIfAbsent(key, ignored -> new GroupAccumulator());
      group.add(source.baseAmount(), sellerDiscount, platformDiscount, source.feeAmount(),
          source.taxAmount(), buyerTotal, sellerReceive, platformDiscount);
    }

    Map<GroupKey, Long> groupIds = new HashMap<>();
    for (Map.Entry<GroupKey, GroupAccumulator> entry : groups.entrySet()) {
      groupIds.put(entry.getKey(), insertGroup(connection, checkoutId, entry.getKey(), entry.getValue()));
    }

    List<CheckoutGroup> outputGroups = new ArrayList<>();
    Map<String, Long> checkoutLineIds = new HashMap<>();
    for (CheckoutQuoteService.SourceLine source : quote.sources()) {
      PricingEngine.LineResult line = pricingById.get(source.pricingLineId());
      FundingAmounts funding = fundingForLine(quote.pricing().applications(), source.pricingLineId());
      long buyerTotal = Math.addExact(line.finalAmount(), source.taxAmount());
      long sellerReceive = source.sellerUserId() == null ? 0
          : Math.max(0, source.baseAmount() - funding.seller() - source.feeAmount());
      GroupKey key = new GroupKey(source.sourceType() == CartService.SourceType.OFFICIAL_PRODUCT
          ? "OFFICIAL_PURCHASE" : "MARKET_PURCHASE", source.currency(), source.sellerUserId(), source.deliveryMode());
      long groupId = groupIds.get(key);
      String legacyType; long legacyId;
      if (source.sourceType() == CartService.SourceType.OFFICIAL_PRODUCT) {
        OrderService.OrderPlacementResult result = orderService.placeOrderInCheckout(connection, userId,
            source.sourceId(), source.quantity(), checkoutNo + ":official:" + source.cartLineId(),
            source.deliveryMode(), buyerTotal);
        legacyType = "ORDER"; legacyId = readOrderId(connection, result.orderNo());
      } else {
        MarketService.TradeResult result = marketService.buyListingInCheckout(connection, userId,
            source.sourceId(), source.quantity(), checkoutNo + ":market:" + source.cartLineId(),
            source.deliveryMode(), new MarketService.FrozenMarketPricing(buyerTotal, sellerReceive,
                source.feeAmount(), source.taxAmount()));
        legacyType = "MARKET_TRADE"; legacyId = result.tradeId();
      }
      long checkoutLineId = insertLine(connection, groupId, source, line, funding,
          sellerReceive, legacyType, legacyId, quote.pricing().applications());
      checkoutLineIds.put(source.pricingLineId(), checkoutLineId);
      insertPaymentUnits(connection, checkoutLineId, source, line);
      insertFundingShares(connection, checkoutLineId, source.currency(), funding);
      grantMembershipProduct(connection, userId, checkoutId, checkoutLineId, checkoutNo, source);
      outputGroups.add(new CheckoutGroup(key.businessType(), source.currency(), source.sellerUserId(), legacyType, legacyId));
    }
    insertDiscountDetails(connection, checkoutId, quote.pricing().applications(), checkoutLineIds);
    insertSnapshot(connection, checkoutId, quote);
    quoteService.consume(connection, quote.id(), checkoutId);
    cartService.removePurchased(connection, cart.id(), quote.sources().stream()
        .map(CheckoutQuoteService.SourceLine::cartLineId).toList());
    try (PreparedStatement statement = connection.prepareStatement(
        "UPDATE checkout_orders SET status = 'PLACED', updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setLong(1, checkoutId); statement.executeUpdate();
    }
    return new CheckoutResult("CREATED", checkoutNo, "PLACED", List.copyOf(outputGroups), quote.pricing().currencyTotals());
  }

  private long insertCheckout(Connection c, String no, long userId, CheckoutQuoteService.Quote q, String key) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("INSERT INTO checkout_orders (checkout_no, user_id, quote_id, status, idempotency_key, input_hash, algorithm_version, rules_hash) VALUES (?, ?, ?, 'PROCESSING', ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
      s.setString(1, no); s.setLong(2, userId); s.setString(3, q.id()); s.setString(4, key);
      s.setString(5, q.inputHash()); s.setString(6, q.algorithmVersion()); s.setString(7, q.rulesHash()); s.executeUpdate();
      try (ResultSet r = s.getGeneratedKeys()) { if (!r.next()) throw new SQLException("Checkout id missing"); return r.getLong(1); }
    }
  }
  private long insertGroup(Connection c, long checkoutId, GroupKey k, GroupAccumulator a) throws SQLException { String no = "CHG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase(); try (PreparedStatement s = c.prepareStatement("INSERT INTO checkout_order_groups (checkout_id, group_no, business_type, currency_space, currency, seller_user_id, delivery_mode, status, base_amount, seller_discount_amount, platform_discount_amount, fee_amount, tax_amount, buyer_total, seller_receive, platform_funding) VALUES (?, ?, ?, 'WALLET', ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) { int i=1; s.setLong(i++, checkoutId); s.setString(i++, no); s.setString(i++, k.businessType()); s.setString(i++, k.currency()); if(k.sellerId()==null)s.setObject(i++,null);else s.setLong(i++,k.sellerId()); s.setString(i++,k.deliveryMode()); s.setLong(i++,a.base); s.setLong(i++,a.sellerDiscount); s.setLong(i++,a.platformDiscount); s.setLong(i++,a.fee); s.setLong(i++,a.tax); s.setLong(i++,a.buyerTotal); s.setLong(i++,a.sellerReceive); s.setLong(i,a.platformFunding); s.executeUpdate(); try(ResultSet r=s.getGeneratedKeys()){if(!r.next())throw new SQLException("Group id missing");return r.getLong(1);}} }
  private long insertLine(Connection c, long groupId, CheckoutQuoteService.SourceLine source,
      PricingEngine.LineResult line, FundingAmounts funding, long sellerReceive,
      String legacyType, long legacyId, List<PricingEngine.Application> applications)
      throws SQLException {
    String sql = "INSERT INTO checkout_order_lines (group_id, source_type, source_id, "
        + "source_version, quantity, currency, base_amount, seller_discount_amount, "
        + "platform_discount_amount, benefit_offset_amount, fee_amount, tax_amount, "
        + "final_amount, seller_receive, platform_funding, fulfillment_ref_type, "
        + "fulfillment_ref_id, status, snapshot_json) "
        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)";
    try (PreparedStatement s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      int i = 1;
      s.setLong(i++, groupId);
      s.setString(i++, source.sourceType().name());
      s.setLong(i++, source.sourceId());
      s.setString(i++, source.sourceVersion());
      s.setInt(i++, source.quantity());
      s.setString(i++, source.currency());
      s.setLong(i++, source.baseAmount());
      s.setLong(i++, funding.seller());
      s.setLong(i++, funding.platform());
      s.setLong(i++, source.feeAmount());
      s.setLong(i++, source.taxAmount());
      s.setLong(i++, line.finalAmount() + source.taxAmount());
      s.setLong(i++, sellerReceive);
      s.setLong(i++, funding.platform());
      s.setString(i++, legacyType);
      s.setLong(i++, legacyId);
      s.setString(i, gson.toJson(Map.of("source", source, "pricing", line,
          "applications", applications)));
      s.executeUpdate();
      try (ResultSet keys = s.getGeneratedKeys()) {
        if (!keys.next()) {
          throw new SQLException("Checkout line id missing");
        }
        return keys.getLong(1);
      }
    }
  }

  private void insertPaymentUnits(Connection connection, long checkoutLineId,
      CheckoutQuoteService.SourceLine source, PricingEngine.LineResult line) throws SQLException {
    List<Long> baseUnits = com.webshopx.promotion.pricing.AllocationEngine.allocateUnits(
        source.baseAmount(), source.quantity());
    List<Long> taxUnits = com.webshopx.promotion.pricing.AllocationEngine.allocateUnits(
        source.taxAmount(), source.quantity());
    String sql = "INSERT INTO checkout_line_payment_units (checkout_line_id, unit_index, "
        + "base_amount, discount_amount, final_amount) VALUES (?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < source.quantity(); index++) {
        long finalAmount = line.unitFinalAmounts().get(index) + taxUnits.get(index);
        statement.setLong(1, checkoutLineId);
        statement.setInt(2, index);
        statement.setLong(3, baseUnits.get(index));
        statement.setLong(4, baseUnits.get(index) - line.unitFinalAmounts().get(index));
        statement.setLong(5, finalAmount);
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  private void insertFundingShares(Connection connection, long checkoutLineId, String currency,
      FundingAmounts funding) throws SQLException {
    String sql = "INSERT INTO checkout_funding_shares (checkout_line_id, funder_type, amount, "
        + "currency) VALUES (?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      if (funding.platform() > 0) {
        statement.setLong(1, checkoutLineId);
        statement.setString(2, "PLATFORM");
        statement.setLong(3, funding.platform());
        statement.setString(4, currency);
        statement.addBatch();
      }
      if (funding.seller() > 0) {
        statement.setLong(1, checkoutLineId);
        statement.setString(2, "SELLER");
        statement.setLong(3, funding.seller());
        statement.setString(4, currency);
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  private void insertDiscountDetails(Connection connection, long checkoutId,
      List<PricingEngine.Application> applications, Map<String, Long> checkoutLineIds)
      throws SQLException {
    String discountSql = "INSERT INTO checkout_discounts (checkout_id, rule_version_id, "
        + "rule_id, user_coupon_id, layer, stacking_slot, discount_amount, funding_mode, "
        + "platform_funding, seller_funding, application_json) "
        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    String allocationSql = "INSERT INTO checkout_discount_allocations "
        + "(checkout_discount_id, checkout_line_id, allocated_amount) VALUES (?, ?, ?)";
    for (PricingEngine.Application application : applications) {
      long discountId;
      try (PreparedStatement statement = connection.prepareStatement(
          discountSql, Statement.RETURN_GENERATED_KEYS)) {
        statement.setLong(1, checkoutId);
        try {
          statement.setLong(2, Long.parseLong(application.ruleId()));
        } catch (NumberFormatException ignored) {
          statement.setObject(2, null);
        }
        statement.setString(3, application.ruleId());
        if (application.userCouponId() == null) {
          statement.setObject(4, null);
        } else {
          statement.setLong(4, application.userCouponId());
        }
        statement.setInt(5, application.layer());
        statement.setString(6, application.slot());
        statement.setLong(7, application.discountAmount());
        String fundingMode = application.funding().platformAmount() == 0 ? "SELLER"
            : application.funding().sellerAmount() == 0 ? "PLATFORM" : "SHARED";
        statement.setString(8, fundingMode);
        statement.setLong(9, application.funding().platformAmount());
        statement.setLong(10, application.funding().sellerAmount());
        statement.setString(11, gson.toJson(application));
        statement.executeUpdate();
        try (ResultSet keys = statement.getGeneratedKeys()) {
          if (!keys.next()) {
            throw new SQLException("Checkout discount id missing");
          }
          discountId = keys.getLong(1);
        }
      }
      try (PreparedStatement statement = connection.prepareStatement(allocationSql)) {
        for (Map.Entry<String, Long> allocation : application.allocations().entrySet()) {
          Long checkoutLineId = checkoutLineIds.get(allocation.getKey());
          if (checkoutLineId == null || allocation.getValue() == 0) {
            continue;
          }
          statement.setLong(1, discountId);
          statement.setLong(2, checkoutLineId);
          statement.setLong(3, allocation.getValue());
          statement.addBatch();
        }
        statement.executeBatch();
      }
    }
  }

  private void grantMembershipProduct(Connection connection, long userId, long checkoutId,
      long checkoutLineId, String checkoutNo, CheckoutQuoteService.SourceLine source)
      throws SQLException {
    if (source.sourceType() != CartService.SourceType.OFFICIAL_PRODUCT) {
      return;
    }
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT plan_version_id FROM membership_product_bindings "
            + "WHERE product_id = ? AND active = TRUE")) {
      statement.setLong(1, source.sourceId());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return;
        }
        if (source.quantity() != 1) {
          throw new ServiceException("membership_quantity_invalid",
              "Membership products must be purchased one at a time");
        }
        long versionId = result.getLong(1);
        String grantKey = checkoutNo + ":membership:" + checkoutLineId;
        MembershipService.Membership membership = membershipService.grant(connection, userId,
            versionId, "PURCHASE", checkoutNo, grantKey, null);
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO benefit_grants (checkout_id, order_line_id, benefit_type, "
                + "benefit_ref, quantity, trigger_status, status, grant_biz_key, granted_at, "
                + "detail_json) VALUES (?, ?, 'MEMBERSHIP', ?, 1, 'PAID', 'GRANTED', ?, "
                + "CURRENT_TIMESTAMP, ?)")) {
          insert.setLong(1, checkoutId);
          insert.setLong(2, checkoutLineId);
          insert.setString(3, String.valueOf(membership.id()));
          insert.setString(4, grantKey);
          insert.setString(5, gson.toJson(membership));
          insert.executeUpdate();
        }
      }
    }
  }
  private void insertSnapshot(Connection c,long checkoutId,CheckoutQuoteService.Quote q)throws SQLException{String json=gson.toJson(q);try(PreparedStatement s=c.prepareStatement("INSERT INTO checkout_price_snapshots (checkout_id, quote_id, algorithm_version, input_hash, rules_hash, snapshot_json, snapshot_hash) VALUES (?, ?, ?, ?, ?, ?, ?)")){s.setLong(1,checkoutId);s.setString(2,q.id());s.setString(3,q.algorithmVersion());s.setString(4,q.inputHash());s.setString(5,q.rulesHash());s.setString(6,json);s.setString(7,PricingEngine.stableHash(json));s.executeUpdate();}}
  private long readOrderId(Connection c,String orderNo)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT id FROM orders WHERE order_no = ?")){s.setString(1,orderNo);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Legacy order missing");return r.getLong(1);}}}
  private ExistingCheckout findExisting(Connection c,long user,String key)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT id, input_hash FROM checkout_orders WHERE user_id = ? AND idempotency_key = ?")){s.setLong(1,user);s.setString(2,key);try(ResultSet r=s.executeQuery()){return r.next()?new ExistingCheckout(r.getLong(1),r.getString(2)):null;}}}
  private CheckoutResult readResult(Connection c,long id,String state)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT checkout_no,status FROM checkout_orders WHERE id = ?")){s.setLong(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Checkout missing");return new CheckoutResult(state,r.getString(1),r.getString(2),List.of(),Map.of());}}}
  private FundingAmounts fundingForLine(List<PricingEngine.Application> apps,String lineId){long platform=0,seller=0;for(PricingEngine.Application a:apps){long allocated=a.allocations().getOrDefault(lineId,0L);if(allocated==0)continue;long platformPart=a.discountAmount()==0?0:MathSupport.roundHalfUp(allocated,(int)(a.funding().platformAmount()*10000/a.discountAmount()));platform=Math.addExact(platform,platformPart);seller=Math.addExact(seller,allocated-platformPart);}return new FundingAmounts(platform,seller);}
  private record ExistingCheckout(long id,String inputHash){}
  private record FundingAmounts(long platform,long seller){}
  private record GroupKey(String businessType,String currency,Long sellerId,String deliveryMode){}
  private static final class GroupAccumulator{long base,sellerDiscount,platformDiscount,fee,tax,buyerTotal,sellerReceive,platformFunding;void add(long b,long sd,long pd,long f,long t,long bt,long sr,long pf){base+=b;sellerDiscount+=sd;platformDiscount+=pd;fee+=f;tax+=t;buyerTotal+=bt;sellerReceive+=sr;platformFunding+=pf;}}
  record SubmitCommand(String quoteId,long cartVersion,String idempotencyKey){}
  record CheckoutGroup(String businessType,String currency,Long sellerUserId,String legacyType,long legacyId){}
  record CheckoutResult(String state,String checkoutNo,String status,List<CheckoutGroup> groups,Map<String,PricingEngine.CurrencyTotal> currencyTotals){}
}
