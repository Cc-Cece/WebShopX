package com.webshopx;

import com.google.gson.Gson;
import com.webshopx.promotion.pricing.PricingEngine;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Builds and persists short-lived, replayable cart quotes. */
class CheckoutQuoteService {
  private final DatabaseManager databaseManager;
  private final CartService cartService;
  private final ProductService productService;
  private final MarketService marketService;
  private final PromotionService promotionService;
  private final MembershipService membershipService;
  private final PricingEngine pricingEngine = new PricingEngine();
  private final Gson gson = CommerceJson.create();

  CheckoutQuoteService(
      DatabaseManager databaseManager,
      CartService cartService,
      ProductService productService,
      MarketService marketService,
      PromotionService promotionService,
      MembershipService membershipService) {
    this.databaseManager = databaseManager;
    this.cartService = cartService;
    this.productService = productService;
    this.marketService = marketService;
    this.promotionService = promotionService;
    this.membershipService = membershipService;
  }

  Quote quote(long userId, QuoteCommand command) {
    CartService.CartView cart = cartService.get(userId);
    if (cart.version() != command.cartVersion())
      throw new ServiceException("CART_VERSION_CONFLICT", "Cart changed");
    Set<Long> requested =
        command.lineIds() == null || command.lineIds().isEmpty()
            ? cart.lines().stream()
                .filter(CartService.CartLine::selected)
                .map(CartService.CartLine::id)
                .collect(java.util.stream.Collectors.toSet())
            : Set.copyOf(command.lineIds());
    List<SourceLine> sourceLines = new ArrayList<>();
    List<PricingEngine.Line> pricingLines = new ArrayList<>();
    for (CartService.CartLine line : cart.lines()) {
      if (!requested.contains(line.id())) continue;
      SourceLine source = assembleSource(userId, line);
      sourceLines.add(source);
      pricingLines.add(
          new PricingEngine.Line(
              source.pricingLineId(),
              source.currency(),
              source.baseAmount(),
              source.quantity(),
              source.sellerUserId() == null ? null : String.valueOf(source.sellerUserId())));
    }
    if (sourceLines.isEmpty())
      throw new ServiceException("cart_empty", "No cart lines are selected");
    Instant now = Instant.now();
    Set<String> ids =
        pricingLines.stream()
            .map(PricingEngine.Line::id)
            .collect(java.util.stream.Collectors.toSet());
    List<PricingEngine.Rule> rules = promotionService.activeRules(userId, now, ids);
    Set<String> entitlements =
        databaseManager.withConnection(c -> membershipService.activeEntitlements(c, userId, now));
    PricingEngine.Context context =
        new PricingEngine.Context(
            now,
            pricingLines,
            rules,
            entitlements,
            command.selectedRuleIds() == null ? Set.of() : Set.copyOf(command.selectedRuleIds()),
            command.disabledRuleIds() == null ? Set.of() : Set.copyOf(command.disabledRuleIds()),
            1,
            50,
            1_000_000);
    PricingEngine.Result result;
    try {
      result = pricingEngine.calculate(context);
    } catch (PricingEngine.PricingException exception) {
      throw new ServiceException(
          exception.code(), "Selected promotion combination is not available");
    }
    String inputHash = inputHash(cart, sourceLines, command);
    String rulesHash =
        PricingEngine.stableHash(
            rules.stream().map(PricingEngine.Rule::id).sorted().toList().toString());
    Instant expires =
        now.plusSeconds(
            sourceLines.stream()
                    .anyMatch(line -> line.sourceType() == CartService.SourceType.MARKET_LISTING)
                ? 30
                : 60);
    String quoteId = "pq_" + UUID.randomUUID();
    Set<String> selectedRuleIds =
        command.selectedRuleIds() == null ? Set.of() : Set.copyOf(command.selectedRuleIds());
    Set<String> disabledRuleIds =
        command.disabledRuleIds() == null ? Set.of() : Set.copyOf(command.disabledRuleIds());
    Quote quote =
        new Quote(
            quoteId,
            userId,
            cart.id(),
            cart.version(),
            inputHash,
            rulesHash,
            PricingEngine.ALGORITHM_VERSION,
            now,
            expires,
            selectedRuleIds,
            disabledRuleIds,
            sourceLines,
            result,
            "ACTIVE");
    databaseManager.inTransaction(
        connection -> {
          persist(connection, quote);
          return null;
        });
    return quote;
  }

  Quote read(long userId, String quoteId, boolean lock) {
    return databaseManager.withConnection(connection -> read(connection, userId, quoteId, lock));
  }

  Quote read(Connection connection, long userId, String quoteId, boolean lock) throws SQLException {
    String sql =
        "SELECT result_json, expires_at, status FROM checkout_quotes WHERE id = ? AND user_id = ?"
            + (lock ? databaseManager.sqlProvider().forUpdateClause() : "");
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, quoteId);
      statement.setLong(2, userId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new ServiceException("quote_missing", "Quote is not visible");
        Quote quote = gson.fromJson(result.getString("result_json"), Quote.class);
        if (!"ACTIVE".equals(result.getString("status")))
          throw new ServiceException("QUOTE_INPUT_CHANGED", "Quote is not active");
        if (!Instant.now().isBefore(result.getTimestamp("expires_at").toInstant()))
          throw new ServiceException("QUOTE_EXPIRED", "Quote expired");
        return quote;
      }
    }
  }

  void consume(Connection connection, String quoteId, long checkoutId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE checkout_quotes SET status = 'CONSUMED', consumed_checkout_id = ? WHERE id = ?"
                + " AND status = 'ACTIVE'")) {
      statement.setLong(1, checkoutId);
      statement.setString(2, quoteId);
      if (statement.executeUpdate() != 1)
        throw new ServiceException("QUOTE_INPUT_CHANGED", "Quote was already consumed");
    }
  }

  private SourceLine assembleSource(long userId, CartService.CartLine line) {
    if (line.sourceType() == CartService.SourceType.OFFICIAL_PRODUCT) {
      ProductService.ProductView product =
          productService.listActiveProductsForUser(userId).stream()
              .filter(item -> item.id() == line.sourceId())
              .findFirst()
              .orElseThrow(
                  () -> new ServiceException("SOURCE_NOT_CARTABLE", "Product is unavailable"));
      if (product.productSemantic() == ProductService.ProductSemantic.RECYCLE)
        throw new ServiceException("SOURCE_NOT_CARTABLE", "Recycle products use recycle quote");
      ProductService.ProductPriceQuote price =
          productService.quoteOrderPrice(product, line.quantity());
      return new SourceLine(
          line.id(),
          line.sourceType(),
          line.sourceId(),
          "OFFICIAL_PRODUCT:" + line.sourceId(),
          line.quantity(),
          product.currency().name(),
          price.totalAmount(),
          null,
          line.deliveryMode(),
          String.valueOf(price.currentDemandScore()),
          price.firstUnitPrice(),
          price.lastUnitPrice(),
          price.averageUnitPrice(),
          0,
          0);
    }
    MarketService.PurchaseQuote price =
        marketService.quotePurchase(userId, line.sourceId(), line.quantity());
    Long sellerId =
        databaseManager.withConnection(connection -> sellerId(connection, line.sourceId()));
    return new SourceLine(
        line.id(),
        line.sourceType(),
        line.sourceId(),
        "MARKET_LISTING:" + line.sourceId(),
        line.quantity(),
        price.currency().name(),
        price.totalPrice(),
        sellerId,
        line.deliveryMode(),
        String.valueOf(price.currentDemandScore()),
        price.firstUnitPrice(),
        price.lastUnitPrice(),
        price.averageUnitPrice(),
        price.feeAmount(),
        price.taxAmount());
  }

  private Long sellerId(Connection connection, long listingId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT seller_user_id FROM market_listings WHERE id = ?")) {
      statement.setLong(1, listingId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next())
          throw new ServiceException("LISTING_UNAVAILABLE", "Listing is unavailable");
        return result.getLong(1);
      }
    }
  }

  private void persist(Connection connection, Quote quote) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO checkout_quotes (id, quote_type, user_id, cart_id, cart_version,"
                + " selection_mode, input_hash, rules_hash, algorithm_version,"
                + " currency_totals_json, result_json, explanation_json, status, expires_at) VALUES"
                + " (?, 'CART', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)")) {
      statement.setString(1, quote.id());
      statement.setLong(2, quote.userId());
      statement.setLong(3, quote.cartId());
      statement.setLong(4, quote.cartVersion());
      statement.setString(5, quote.selectedRuleIds().isEmpty() ? "AUTO_BEST" : "MANUAL");
      statement.setString(6, quote.inputHash());
      statement.setString(7, quote.rulesHash());
      statement.setString(8, quote.algorithmVersion());
      statement.setString(9, gson.toJson(quote.pricing().currencyTotals()));
      statement.setString(10, gson.toJson(quote));
      statement.setString(11, gson.toJson(quote.pricing().rejections()));
      statement.setTimestamp(12, Timestamp.from(quote.expiresAt()));
      statement.executeUpdate();
    }
  }

  private String inputHash(
      CartService.CartView cart, List<SourceLine> lines, QuoteCommand command) {
    List<String> selectedRules =
        command.selectedRuleIds() == null
            ? List.of()
            : command.selectedRuleIds().stream().sorted().toList();
    List<String> disabledRules =
        command.disabledRuleIds() == null
            ? List.of()
            : command.disabledRuleIds().stream().sorted().toList();
    return PricingEngine.stableHash(
        cart.id()
            + "|"
            + cart.version()
            + "|"
            + lines.stream()
                .sorted(java.util.Comparator.comparing(SourceLine::pricingLineId))
                .toList()
            + "|"
            + selectedRules
            + "|"
            + disabledRules);
  }

  record QuoteCommand(
      long cartVersion,
      List<Long> lineIds,
      Set<String> selectedRuleIds,
      Set<String> disabledRuleIds) {}

  record SourceLine(
      long cartLineId,
      CartService.SourceType sourceType,
      long sourceId,
      String pricingLineId,
      int quantity,
      String currency,
      long baseAmount,
      Long sellerUserId,
      String deliveryMode,
      String sourceVersion,
      long firstUnitPrice,
      long lastUnitPrice,
      long averageUnitPrice,
      long feeAmount,
      long taxAmount) {}

  record Quote(
      String id,
      long userId,
      long cartId,
      long cartVersion,
      String inputHash,
      String rulesHash,
      String algorithmVersion,
      Instant createdAt,
      Instant expiresAt,
      Set<String> selectedRuleIds,
      Set<String> disabledRuleIds,
      List<SourceLine> sources,
      PricingEngine.Result pricing,
      String status) {}
}
