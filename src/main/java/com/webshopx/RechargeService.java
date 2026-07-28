package com.webshopx;

import com.google.gson.Gson;
import com.webshopx.payment.api.PaymentMethod;
import com.webshopx.payment.api.PaymentConfigUpdateRequest;
import com.webshopx.payment.api.PaymentConfigUpdateResult;
import java.net.URLEncoder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

class RechargeService {
  private static final DateTimeFormatter ORDER_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
  private static final String DEFAULT_PROVIDER_NAME = "payment";
  private static final String LEDGER_BIZ_TYPE = "RECHARGE_PAYMENT";
  private static final String DEFAULT_CURRENCY = "CNY";

  private final DatabaseManager databaseManager;
  private final SqlProvider sqlProvider;
  private final WalletService walletService;
  private final WebShopXPaymentBridge paymentBridge;
  private final Supplier<PluginSettings> settingsSupplier;
  private final SecureRandom secureRandom = new SecureRandom();
  private final Gson gson = new Gson();

  RechargeService(
      DatabaseManager databaseManager,
      WalletService walletService,
      WebShopXPaymentBridge paymentBridge,
      Supplier<PluginSettings> settingsSupplier) {
    this.databaseManager = databaseManager;
    this.sqlProvider = databaseManager.sqlProvider();
    this.walletService = walletService;
    this.paymentBridge = paymentBridge;
    this.settingsSupplier = settingsSupplier;
  }

  boolean isPaymentAvailable() {
    return paymentBridge.isAvailable();
  }

  java.util.List<WebShopXPaymentBridge.PaymentProviderInfo> paymentProviderInfos() {
    return paymentBridge.providerInfos();
  }

  java.util.Optional<WebShopXPaymentBridge.PaymentProviderConfiguration> paymentProviderConfiguration(
      String providerId, String locale) {
    return paymentBridge.providerConfiguration(providerId, locale);
  }

  PaymentConfigUpdateResult updatePaymentProviderConfiguration(String providerId, PaymentConfigUpdateRequest request) {
    return paymentBridge.updateProviderConfiguration(providerId, request);
  }

  void registerPaymentListener() {
    paymentBridge.registerPaymentListener(this::handlePaymentNotify);
  }

  void unregisterPaymentListener() {
    paymentBridge.unregisterPaymentListener();
  }

  RechargeCreateResult createRechargeOrder(RechargeCreateRequest request) {
    RechargeCreateRequest normalized = normalizeCreateRequest(request);
    PluginSettings.RechargeRate route = settingsSupplier.get().paymentSettings()
        .rechargeRate(normalized.preferredMethod(), normalized.currency());
    if (route == null || route.providerId().isBlank()) {
      throw new ServiceException("payment_route_not_configured",
          "No payment provider is configured for " + normalized.preferredMethod() + " " + normalized.currency());
    }
    WebShopXPaymentBridge.PaymentProviderInfo provider = paymentBridge.providerInfo(route.providerId());
    if (!provider.supportedMethods().contains(normalized.preferredMethod())
        || !provider.supportedCurrencies().contains(normalized.currency())) {
      throw new ServiceException("payment_route_unsupported",
          "Payment provider " + route.providerId() + " does not support this method and currency");
    }
    String orderId = generateOrderId();
    Instant requestedExpiresAt = resolveRechargeOrderExpiresAt();
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("source", normalized.source());
    metadata.put("coinAmount", String.valueOf(normalized.coinAmount()));

    databaseManager.inTransaction(connection -> {
      ensureUserExists(connection, normalized.userId());
      insertRechargeOrder(connection, orderId, normalized, route.providerId(), gson.toJson(metadata));
      return null;
    });

    String dynamicReturnUrl = null;
    if (normalized.baseUrl() != null && !normalized.baseUrl().isBlank()) {
      String locale = normalizeLocaleTag(normalized.locale());
      if (locale == null) {
        dynamicReturnUrl = normalized.baseUrl() + "/result.html?orderId=" + orderId;
      } else {
        dynamicReturnUrl = normalized.baseUrl()
            + "/result.html?orderId=" + orderId
            + "&locale=" + URLEncoder.encode(locale, StandardCharsets.UTF_8);
      }
    }

    WebShopXPaymentBridge.CreatePaymentResultData payResult;
    try {
      payResult = paymentBridge.createPayment(route.providerId(), new WebShopXPaymentBridge.CreatePaymentRequestData(
          orderId,
          String.valueOf(normalized.userId()),
          normalized.playerUuid(),
          normalized.amountMinor(),
          normalized.currency(),
          "WebShopX Recharge " + normalized.coinAmount() + " ShopCoin",
          "Recharge " + normalized.coinAmount() + " ShopCoin via " + normalized.source(),
          normalized.preferredMethod(),
          normalized.methodCode(),
          dynamicReturnUrl,
          null,
          requestedExpiresAt,
          metadata));
    } catch (ServiceException exception) {
      markOrderFailed(orderId, exception.code(), exception.getMessage());
      throw exception;
    }

    if (!payResult.success()) {
      markOrderFailed(orderId, payResult.errorCode(), payResult.message());
      return RechargeCreateResult.fail(
          orderId,
          payResult.errorCode() == null ? "payment_create_failed" : payResult.errorCode(),
          payResult.message() == null ? "Payment provider createPayment failed" : payResult.message());
    }

    Instant expireTime = mergeExpireTime(requestedExpiresAt, payResult.expireTime());

    databaseManager.inTransaction(connection -> {
      updateOrderPaying(connection, orderId, payResult, expireTime);
      return null;
    });

    return new RechargeCreateResult(
        true,
        orderId,
        payResult.providerOrderId(),
        payResult.payUrl(),
        payResult.qrCodeUrl(),
          expireTime,
        null,
        "success");
  }

  WebShopXPaymentBridge.NotifyResultData handlePaymentNotify(WebShopXPaymentBridge.PaymentNotifyData notify) {
    if (notify == null || isBlank(notify.merchantOrderId())) {
      return WebShopXPaymentBridge.NotifyResultData.fail("payment_order_mismatch", "missing merchantOrderId");
    }
    try {
      return databaseManager.inTransaction(connection -> applyProviderResult(connection, notify));
    } catch (ServiceException exception) {
      return WebShopXPaymentBridge.NotifyResultData.fail(exception.code(), exception.getMessage());
    } catch (RuntimeException exception) {
      return WebShopXPaymentBridge.NotifyResultData.fail("internal_error", exception.getMessage());
    }
  }

  RechargeCancelResult cancelRechargeOrder(long userId, String orderId) {
    String normalizedOrderId = normalizeOrderId(orderId);
    return databaseManager.inTransaction(connection -> {
      RechargeOrder order = readOrderForUpdate(connection, normalizedOrderId);
      if (order == null || order.userId() != userId) {
        return RechargeCancelResult.fail(normalizedOrderId, null, "ORDER_NOT_FOUND", "Recharge order not found");
      }
      if (order.status() == RechargeOrderStatus.PAID) {
        return RechargeCancelResult.fail(
            order.orderId(),
            order.status().name(),
            "INVALID_STATUS",
            "Paid order cannot be cancelled");
      }
      if (order.status().isTerminal()) {
        return new RechargeCancelResult(true, order.orderId(), order.status().name(), null, "order already closed");
      }
      paymentBridge.cancelPayment(order.provider(), order.orderId(), order.providerOrderId());
      markOrderTerminal(connection, order.orderId(), RechargeOrderStatus.CLOSED);
      return new RechargeCancelResult(true, order.orderId(), RechargeOrderStatus.CLOSED.name(), null, "success");
    });
  }

  FixRechargeResult fixRechargeOrder(String orderId) {
    String normalizedOrderId = normalizeOrderId(orderId);
    RechargeOrder order = findOrder(normalizedOrderId);
    if (order == null) {
      return FixRechargeResult.fail(normalizedOrderId, null, "ORDER_NOT_FOUND", "Recharge order not found");
    }
    if (order.status() == RechargeOrderStatus.PAID) {
      return new FixRechargeResult(
          true,
          order.orderId(),
          order.providerOrderId(),
          false,
          order.status().name(),
          null,
          "order already processed");
    }
    if (isBlank(order.providerOrderId())) {
      return FixRechargeResult.fail(order.orderId(), null, "INVALID_STATUS", "providerOrderId is missing");
    }

    WebShopXPaymentBridge.QueryPaymentResultData query =
        paymentBridge.queryPayment(order.provider(), order.orderId(), order.providerOrderId());
    if (!query.success()) {
      return FixRechargeResult.fail(
          order.orderId(),
          order.providerOrderId(),
          query.errorCode() == null ? "payment_query_failed" : query.errorCode(),
          query.message() == null ? "Payment provider queryPayment failed" : query.message());
    }

    RechargeOrder before = findOrder(normalizedOrderId);
    WebShopXPaymentBridge.NotifyResultData notifyResult = handlePaymentNotify(
        new WebShopXPaymentBridge.PaymentNotifyData(
        order.provider(),
        query.merchantOrderId(),
        query.providerOrderId(),
        query.status(),
        query.amountMinor(),
        query.currency(),
        query.method(),
        query.payTime(),
        null,
        Map.of("source", "fix")));
    RechargeOrder after = findOrder(normalizedOrderId);
    boolean fixed = before != null
        && after != null
        && before.status() != RechargeOrderStatus.PAID
        && after.status() == RechargeOrderStatus.PAID;
    return new FixRechargeResult(
        notifyResult.success(),
        order.orderId(),
        order.providerOrderId(),
        fixed,
        after == null ? order.status().name() : after.status().name(),
        notifyResult.success() ? null : notifyResult.code(),
        notifyResult.message());
  }

  RechargeOrder findOwnedOrder(long userId, String orderId) {
    RechargeOrder order = findOrder(normalizeOrderId(orderId));
    if (order == null || order.userId() != userId) {
      return null;
    }
    return order;
  }

  UserBinding findUserByPlayer(UUID playerUuid) {
    if (playerUuid == null) {
      return null;
    }
    return databaseManager.withConnection(connection -> {
      String sql = "SELECT id, username, bound_uuid FROM web_users WHERE bound_uuid = ? LIMIT 1";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, playerUuid.toString());
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return null;
          }
          return new UserBinding(
              resultSet.getLong("id"),
              resultSet.getString("username"),
              UUID.fromString(resultSet.getString("bound_uuid")));
        }
      }
    });
  }

  long amountToCoinAmount(long amountMinor) {
    PluginSettings.PaymentSettings paymentSettings = settingsSupplier.get().paymentSettings();
    PaymentMethod defaultMethod = paymentSettings.rechargeMethods().isEmpty() ? PaymentMethod.ALIPAY : paymentSettings.rechargeMethods().get(0);
    return calculateCoinAmount(
        amountMinor,
        paymentSettings.primaryRechargeCurrency(),
        defaultMethod);
  }

  long calculateCoinAmount(long amountMinor, String currency, PaymentMethod method) {
    if (amountMinor <= 0L) {
      throw new ServiceException("invalid_amount", "Recharge amount must be positive");
    }
    PluginSettings.PaymentSettings paymentSettings = settingsSupplier.get().paymentSettings();
    PluginSettings.RechargeRate rate = paymentSettings.rechargeRate(method, currency);
    if (rate == null) {
      throw new ServiceException(
          "UNSUPPORTED_RECHARGE_RATE",
          "Recharge rate is not configured for " + method + " " + currency);
    }
    try {
      return BigDecimal.valueOf(amountMinor)
          .multiply(BigDecimal.valueOf(rate.coinsPerUnit()))
          .divide(BigDecimal.valueOf(100L), 0, RoundingMode.HALF_UP)
          .longValueExact();
    } catch (ArithmeticException exception) {
      throw new ServiceException("invalid_amount", "Recharge coin amount is out of range");
    }
  }

  long yuanToAmountMinor(String rawAmount) {
    if (rawAmount == null || rawAmount.isBlank()) {
      throw new ServiceException("invalid_amount", "Recharge amount is required");
    }
    String text = rawAmount.trim();
    if (!text.matches("^[0-9]+(\\.[0-9]{1,2})?$")) {
      throw new ServiceException("invalid_amount", "Amount must be a positive number with at most 2 decimals");
    }
    String[] parts = text.split("\\.", 2);
    long yuan = Long.parseLong(parts[0]);
    String centsText = parts.length == 2 ? (parts[1] + "00").substring(0, 2) : "00";
    long cents = Long.parseLong(centsText);
    long amountMinor = Math.addExact(Math.multiplyExact(yuan, 100L), cents);
    if (amountMinor <= 0L) {
      throw new ServiceException("invalid_amount", "Recharge amount must be positive");
    }
    return amountMinor;
  }

  private RechargeCreateRequest normalizeCreateRequest(RechargeCreateRequest request) {
    if (request == null) {
      throw new ServiceException("bad_request", "Recharge request is required");
    }
    long userId = request.userId();
    if (userId <= 0L) {
      throw new ServiceException("user_missing", "User id is required");
    }
    long amountMinor = request.amountMinor();
    if (amountMinor <= 0L) {
      throw new ServiceException("INVALID_AMOUNT", "amountMinor must be greater than 0");
    }
    String currency = normalizeCurrency(request.currency());
    PaymentMethod preferredMethod = normalizePaymentMethod(request.preferredMethod());
    long coinAmount = calculateCoinAmount(amountMinor, currency, preferredMethod);
    String source = normalizeSource(request.source());
    if ("MINECRAFT".equals(source) && request.playerUuid() == null) {
      throw new ServiceException("bad_request", "playerUuid is required for Minecraft recharge");
    }
    return new RechargeCreateRequest(
        userId,
        request.playerUuid(),
        amountMinor,
        currency,
        coinAmount,
        preferredMethod,
        blankToNull(request.methodCode()),
        source,
        request.baseUrl(),
        normalizeLocaleTag(request.locale()));
  }

  private Instant resolveRechargeOrderExpiresAt() {
    int expireMinutes = settingsSupplier.get().rechargeOrderExpireMinutes();
    if (expireMinutes <= 0) {
      return null;
    }
    return Instant.now().plusSeconds(expireMinutes * 60L);
  }

  private Instant mergeExpireTime(Instant requested, Instant upstream) {
    if (requested == null) {
      return upstream;
    }
    if (upstream == null) {
      return requested;
    }
    return requested.isBefore(upstream) ? requested : upstream;
  }

  private String normalizeCurrency(String raw) {
    String normalized = raw == null || raw.isBlank() ? DEFAULT_CURRENCY : raw.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("^[A-Z]{3,8}$")) {
      throw new ServiceException("UNSUPPORTED_CURRENCY", "Unsupported currency: " + raw);
    }
    if (!settingsSupplier.get().paymentSettings().isCurrencyAllowed(normalized)) {
      throw new ServiceException("UNSUPPORTED_CURRENCY", "Unsupported currency: " + normalized);
    }
    return normalized;
  }

  private PaymentMethod normalizePaymentMethod(PaymentMethod method) {
    PluginSettings.PaymentSettings paymentSettings = settingsSupplier.get().paymentSettings();
    PaymentMethod normalized = method;
    if (normalized == null) {
      normalized = paymentSettings.rechargeMethods().isEmpty() ? PaymentMethod.ALIPAY : paymentSettings.rechargeMethods().get(0);
    }
    if (!paymentSettings.isMethodAllowed(normalized)) {
      throw new ServiceException("METHOD_UNSUPPORTED", "Unsupported payment method: " + normalized.name());
    }
    return normalized;
  }

  private String normalizeSource(String raw) {
    String normalized = raw == null || raw.isBlank() ? "WEB" : raw.trim().toUpperCase(Locale.ROOT);
    if (!normalized.equals("WEB") && !normalized.equals("MINECRAFT")) {
      throw new ServiceException("bad_request", "Invalid recharge source");
    }
    return normalized;
  }

  private String normalizeLocaleTag(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String text = raw.trim().replace('_', '-');
    if (!text.matches("^[A-Za-z]{2,8}(-[A-Za-z0-9]{2,8})*$")) {
      return null;
    }
    return text;
  }

  private String normalizeOrderId(String orderId) {
    String normalized = orderId == null ? "" : orderId.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("^WSX[0-9A-Z]{8,40}$")) {
      throw new ServiceException("bad_request", "Invalid recharge order id");
    }
    return normalized;
  }

  private String generateOrderId() {
    String date = ORDER_DATE_FORMAT.format(LocalDate.now(settingsSupplier.get().timeZone()));
    for (int attempt = 0; attempt < 20; attempt++) {
      String suffix = Long.toString(secureRandom.nextLong(Long.MAX_VALUE), 36)
          .toUpperCase(Locale.ROOT);
      String orderId = "WSX" + date + suffix.substring(0, Math.min(10, suffix.length()));
      if (!orderExists(orderId)) {
        return orderId;
      }
    }
    throw new ServiceException("internal_error", "Failed to generate recharge order id");
  }

  private boolean orderExists(String orderId) {
    return databaseManager.withConnection(connection -> {
      String sql = "SELECT 1 FROM webshopx_recharge_order WHERE order_id = ? LIMIT 1";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, orderId);
        try (ResultSet resultSet = statement.executeQuery()) {
          return resultSet.next();
        }
      }
    });
  }

  private void ensureUserExists(Connection connection, long userId) throws SQLException {
    String sql = "SELECT 1 FROM web_users WHERE id = ? LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("user_missing", "User not found");
        }
      }
    }
  }

  private void insertRechargeOrder(
      Connection connection,
      String orderId,
      RechargeCreateRequest request,
      String providerId,
      String metadataJson) throws SQLException {
    String sql = """
        INSERT INTO webshopx_recharge_order (
          order_id, user_id, player_uuid, amount_minor, currency, coin_amount,
          status, provider, metadata
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, orderId);
      statement.setLong(2, request.userId());
      statement.setString(3, request.playerUuid() == null ? null : request.playerUuid().toString());
      statement.setLong(4, request.amountMinor());
      statement.setString(5, request.currency());
      statement.setLong(6, request.coinAmount());
      statement.setString(7, RechargeOrderStatus.PENDING.name());
      statement.setString(8, providerId);
      statement.setString(9, metadataJson);
      statement.executeUpdate();
    }
  }

  private void updateOrderPaying(
      Connection connection,
      String orderId,
      WebShopXPaymentBridge.CreatePaymentResultData payResult,
      Instant expireTime) throws SQLException {
    String sql = """
        UPDATE webshopx_recharge_order
        SET status = ?, provider = ?, provider_order_id = ?, pay_url = ?, qr_code_url = ?,
            expire_time = ?, error_code = NULL, error_message = NULL, updated_at = CURRENT_TIMESTAMP
        WHERE order_id = ? AND status = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, RechargeOrderStatus.PAYING.name());
      statement.setString(2, payResult.providerId());
      statement.setString(3, payResult.providerOrderId());
      statement.setString(4, payResult.payUrl());
      statement.setString(5, payResult.qrCodeUrl());
      setTimestamp(statement, 6, expireTime);
      statement.setString(7, orderId);
      statement.setString(8, RechargeOrderStatus.PENDING.name());
      int updated = statement.executeUpdate();
      if (updated == 0) {
        throw new ServiceException("INVALID_STATUS", "Recharge order is no longer pending");
      }
    }
  }

  private void markOrderFailed(String orderId, String errorCode, String message) {
    databaseManager.inTransaction(connection -> {
      String sql = """
          UPDATE webshopx_recharge_order
          SET status = ?, error_code = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP
          WHERE order_id = ? AND status IN ('PENDING', 'PAYING')
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, RechargeOrderStatus.FAILED.name());
        statement.setString(2, blankToNull(errorCode));
        statement.setString(3, blankToNull(message));
        statement.setString(4, orderId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  private WebShopXPaymentBridge.NotifyResultData applyProviderResult(
      Connection connection,
      WebShopXPaymentBridge.PaymentNotifyData notify) throws SQLException {
    RechargeOrder order = readOrderForUpdate(connection, notify.merchantOrderId());
    if (order == null) {
      throw new ServiceException("ORDER_NOT_FOUND", "Recharge order not found");
    }

    RechargeOrderStatus targetStatus = mapProviderStatus(notify.status());
    if (order.status() == RechargeOrderStatus.PAID) {
      return WebShopXPaymentBridge.NotifyResultData.ok("already processed");
    }
    if (order.status().isTerminal()) {
      return WebShopXPaymentBridge.NotifyResultData.ok("ignored closed order");
    }
    if (!order.status().canReceiveProviderResult()) {
      throw new ServiceException("payment_notify_rejected", "Recharge order status does not accept payment results");
    }
    validateNotify(order, notify, targetStatus);

    if (targetStatus == RechargeOrderStatus.PAID) {
      long creditedCoinAmount = notify.creditedCoinAmount() == null
          ? order.coinAmount()
          : notify.creditedCoinAmount();
      if (creditedCoinAmount <= 0L) {
        throw new ServiceException(
            "payment_coin_amount_invalid", "creditedCoinAmount must be greater than 0");
      }
      boolean credited = walletService.applyDelta(
          connection,
          order.userId(),
          CurrencyType.SHOP_COIN,
          creditedCoinAmount,
          LEDGER_BIZ_TYPE,
          order.orderId(),
          false);
      markOrderPaid(connection, order, notify, creditedCoinAmount);
      return WebShopXPaymentBridge.NotifyResultData.ok(credited ? "success" : "already processed");
    }

    markOrderTerminal(connection, order.orderId(), targetStatus);
    return WebShopXPaymentBridge.NotifyResultData.ok("success");
  }

  private void validateNotify(
      RechargeOrder order,
      WebShopXPaymentBridge.PaymentNotifyData notify,
      RechargeOrderStatus targetStatus) {
    if (isBlank(notify.providerId()) || !notify.providerId().equalsIgnoreCase(order.provider())) {
      throw new ServiceException("payment_provider_mismatch", "payment provider mismatch");
    }
    if (!isBlank(order.providerOrderId())
        && !String.valueOf(order.providerOrderId()).equals(String.valueOf(notify.providerOrderId()))) {
      throw new ServiceException("payment_provider_mismatch", "providerOrderId mismatch");
    }
    if (targetStatus != RechargeOrderStatus.PAID) {
      return;
    }
    if (notify.amountMinor() <= 0L) {
      throw new ServiceException("payment_amount_mismatch", "amountMinor must be greater than 0");
    }
    if (order.amountMinor() != notify.amountMinor()) {
      throw new ServiceException("payment_amount_mismatch", "amountMinor mismatch");
    }
    if (!order.currency().equalsIgnoreCase(String.valueOf(notify.currency()))) {
      throw new ServiceException("payment_currency_mismatch", "currency mismatch");
    }
  }

  private RechargeOrderStatus mapProviderStatus(String status) {
    String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "SUCCESS" -> RechargeOrderStatus.PAID;
      case "FAILED" -> RechargeOrderStatus.FAILED;
      case "EXPIRED" -> RechargeOrderStatus.EXPIRED;
      case "CANCELLED", "CANCELED", "CLOSED" -> RechargeOrderStatus.CLOSED;
      default -> throw new ServiceException("payment_unknown_status", "Unsupported payment status: " + status);
    };
  }

  private void markOrderPaid(
      Connection connection,
      RechargeOrder order,
      WebShopXPaymentBridge.PaymentNotifyData notify,
      long creditedCoinAmount) throws SQLException {
    String sql = """
        UPDATE webshopx_recharge_order
        SET status = ?, provider_order_id = COALESCE(provider_order_id, ?), coin_amount = ?,
            paid_time = ?, credited_time = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
        WHERE order_id = ? AND status IN ('PENDING', 'PAYING')
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, RechargeOrderStatus.PAID.name());
      statement.setString(2, notify.providerOrderId());
      statement.setLong(3, creditedCoinAmount);
      setTimestamp(statement, 4, notify.payTime() == null ? Instant.now() : notify.payTime());
      statement.setString(5, order.orderId());
      statement.executeUpdate();
    }
  }

  private void markOrderTerminal(
      Connection connection,
      String orderId,
      RechargeOrderStatus status) throws SQLException {
    String sql = status == RechargeOrderStatus.EXPIRED
        ? "UPDATE webshopx_recharge_order SET status = ?, "
            + "expire_time = COALESCE(expire_time, CURRENT_TIMESTAMP), "
            + "updated_at = CURRENT_TIMESTAMP "
            + "WHERE order_id = ? AND status IN ('PENDING', 'PAYING')"
        : "UPDATE webshopx_recharge_order SET status = ?, updated_at = CURRENT_TIMESTAMP "
            + "WHERE order_id = ? AND status IN ('PENDING', 'PAYING')";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, status.name());
      statement.setString(2, orderId);
      statement.executeUpdate();
    }
  }

  private RechargeOrder readOrderForUpdate(Connection connection, String orderId) throws SQLException {
    String sql = """
        SELECT id, order_id, user_id, player_uuid, amount_minor, currency, coin_amount,
               status, provider, provider_order_id, pay_url, qr_code_url, expire_time,
               paid_time, credited_time, metadata, created_at, updated_at, error_code, error_message
        FROM webshopx_recharge_order
        WHERE order_id = ?
        """ + sqlProvider.forUpdateClause();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, orderId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return toOrder(resultSet);
      }
    }
  }

  private RechargeOrder findOrder(String orderId) {
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT id, order_id, user_id, player_uuid, amount_minor, currency, coin_amount,
                 status, provider, provider_order_id, pay_url, qr_code_url, expire_time,
                 paid_time, credited_time, metadata, created_at, updated_at, error_code, error_message
          FROM webshopx_recharge_order
          WHERE order_id = ?
          LIMIT 1
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, orderId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return null;
          }
          return toOrder(resultSet);
        }
      }
    });
  }

  private RechargeOrder toOrder(ResultSet resultSet) throws SQLException {
    String playerUuid = resultSet.getString("player_uuid");
    return new RechargeOrder(
        resultSet.getLong("id"),
        resultSet.getString("order_id"),
        resultSet.getLong("user_id"),
        isBlank(playerUuid) ? null : UUID.fromString(playerUuid),
        resultSet.getLong("amount_minor"),
        resultSet.getString("currency"),
        resultSet.getLong("coin_amount"),
        RechargeOrderStatus.valueOf(resultSet.getString("status")),
        resultSet.getString("provider"),
        resultSet.getString("provider_order_id"),
        resultSet.getString("pay_url"),
        resultSet.getString("qr_code_url"),
        toInstant(resultSet.getTimestamp("expire_time")),
        toInstant(resultSet.getTimestamp("paid_time")),
        toInstant(resultSet.getTimestamp("credited_time")),
        resultSet.getString("metadata"),
        toInstant(resultSet.getTimestamp("created_at")),
        toInstant(resultSet.getTimestamp("updated_at")),
        resultSet.getString("error_code"),
        resultSet.getString("error_message"));
  }

  private void setTimestamp(PreparedStatement statement, int index, Instant instant) throws SQLException {
    if (instant == null) {
      statement.setTimestamp(index, null);
      return;
    }
    statement.setTimestamp(index, Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneId.systemDefault())));
  }

  private Instant toInstant(Timestamp timestamp) {
    if (timestamp == null) {
      return null;
    }
    return timestamp.toInstant();
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  record RechargeCreateRequest(
      long userId,
      UUID playerUuid,
      long amountMinor,
      String currency,
      long coinAmount,
      PaymentMethod preferredMethod,
      String methodCode,
      String source,
      String baseUrl,
      String locale) {
  }

  record RechargeCreateResult(
      boolean success,
      String orderId,
      String providerOrderId,
      String payUrl,
      String qrCodeUrl,
      Instant expireTime,
      String errorCode,
      String message) {
    static RechargeCreateResult fail(String orderId, String errorCode, String message) {
      return new RechargeCreateResult(false, orderId, null, null, null, null, errorCode, message);
    }
  }

  record RechargeCancelResult(
      boolean success,
      String orderId,
      String status,
      String code,
      String message) {
    static RechargeCancelResult fail(String orderId, String status, String code, String message) {
      return new RechargeCancelResult(false, orderId, status, code, message);
    }
  }

  record FixRechargeResult(
      boolean success,
      String orderId,
      String providerOrderId,
      boolean fixed,
      String status,
      String errorCode,
      String message) {
    static FixRechargeResult fail(String orderId, String providerOrderId, String errorCode, String message) {
      return new FixRechargeResult(false, orderId, providerOrderId, false, null, errorCode, message);
    }
  }

  record RechargeOrder(
      long id,
      String orderId,
      long userId,
      UUID playerUuid,
      long amountMinor,
      String currency,
      long coinAmount,
      RechargeOrderStatus status,
      String provider,
      String providerOrderId,
      String payUrl,
      String qrCodeUrl,
      Instant expireTime,
      Instant paidTime,
      Instant creditedTime,
      String metadata,
      Instant createdAt,
      Instant updatedAt,
      String errorCode,
      String errorMessage) {
  }

  record UserBinding(long userId, String username, UUID playerUuid) {
  }
}
