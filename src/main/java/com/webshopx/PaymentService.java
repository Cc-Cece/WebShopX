package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

class PaymentService {
  private static final String PROVIDER_ALIPAY = "ALIPAY";
  private static final String STATUS_CREATED = "CREATED";
  private static final String STATUS_PAYING = "PAYING";
  private static final String STATUS_PAID = "PAID";
  private static final String STATUS_CLOSED = "CLOSED";
  private static final String STATUS_REFUNDED = "REFUNDED";
  private static final String STATUS_FAILED = "FAILED";
  private static final String BIZ_TYPE_PAYMENT_ALIPAY_IN = "PAYMENT_ALIPAY_IN";
  private static final Set<String> SUCCESS_TRADE_STATUS = Set.of("TRADE_SUCCESS", "TRADE_FINISHED");
  private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.BASIC_ISO_DATE;

  private final DatabaseManager databaseManager;
  private final Supplier<PluginSettings> settingsSupplier;
  private final WalletService walletService;
  private final Gson gson;

  PaymentService(
      DatabaseManager databaseManager,
      Supplier<PluginSettings> settingsSupplier,
      WalletService walletService) {
    this.databaseManager = databaseManager;
    this.settingsSupplier = settingsSupplier;
    this.walletService = walletService;
    this.gson = new Gson();
  }

  CreateOrderResult createAlipayOrder(
      long userId,
      long shopCoinAmount,
      String clientReturnUrl,
      String clientIp,
      String userAgent) {
    PluginSettings.AlipaySettings alipay = requireAlipayEnabled();
    validateRechargeAmount(shopCoinAmount, alipay);
    long amountFen = safeMultiply(shopCoinAmount, alipay.shopcoinUnitPriceFen());
    int expireMinutes = normalizedExpireMinutes(alipay.orderExpireMinutes());
    LocalDateTime now = TimeSupport.utcNow();
    String subject = "WebShopX ShopCoin Recharge";
    String body = "Recharge ShopCoin";
    String returnUrl = sanitizeReturnUrl(clientReturnUrl);

    return databaseManager.inTransaction(connection -> {
      closeExpiredOrders(connection, now);
      enforceCreateRateLimit(connection, userId, now, alipay.maxCreatePerMinute());
      for (int attempt = 0; attempt < 5; attempt++) {
        String orderNo = newOrderNo(now);
        String outTradeNo = orderNo;
        LocalDateTime expireAt = now.plusMinutes(expireMinutes);
        String payUrl = buildPayUrl(alipay, outTradeNo, amountFen, subject, body, expireMinutes, returnUrl);
        if (insertPaymentOrder(
            connection,
            orderNo,
            outTradeNo,
            userId,
            shopCoinAmount,
            amountFen,
            subject,
            body,
            expireAt,
            payUrl,
            clientIp,
            userAgent)) {
          return new CreateOrderResult(
              orderNo,
              outTradeNo,
              shopCoinAmount,
              amountFen,
              STATUS_CREATED,
              payUrl,
              null,
              expireAt);
        }
      }
      throw new ServiceException("conflict", "Failed to allocate payment order number");
    });
  }

  PaymentOrderView getOrderForUser(long userId, String orderNo) {
    String normalized = normalizeOrderNo(orderNo);
    return databaseManager.withConnection(connection -> {
      closeExpiredOrders(connection, TimeSupport.utcNow());
      PaymentOrderView row = readOrderByOrderNo(connection, normalized, userId);
      if (row == null) {
        throw new ServiceException("not_found", "Payment order not found");
      }
      return row;
    });
  }

  List<PaymentOrderView> listOrdersForUser(long userId, int limit) {
    int normalizedLimit = Math.max(1, Math.min(limit, 50));
    return databaseManager.withConnection(connection -> {
      closeExpiredOrders(connection, TimeSupport.utcNow());
      String sql = """
          SELECT order_no, out_trade_no, status, shop_coin_amount, amount_cny_fen,
                 trade_no, notify_time, paid_at, expire_at, created_at, updated_at
          FROM payment_orders
          WHERE user_id = ?
          ORDER BY id DESC
          LIMIT ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, userId);
        statement.setInt(2, normalizedLimit);
        List<PaymentOrderView> rows = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            rows.add(mapOrderRow(resultSet, userId));
          }
        }
        return rows;
      }
    });
  }

  NotifyProcessResult handleAlipayNotify(Map<String, String> payload) {
    if (payload == null || payload.isEmpty()) {
      return new NotifyProcessResult(false, "empty_payload");
    }
    PluginSettings.AlipaySettings alipay = requireAlipayEnabled();
    AlipayClient alipayClient = new AlipayClient(alipay);

    String outTradeNo = trimOrNull(payload.get("out_trade_no"));
    String orderNo = outTradeNo;
    String tradeNo = trimOrNull(payload.get("trade_no"));
    String notifyId = trimOrNull(payload.get("notify_id"));
    String tradeStatus = trimOrNull(payload.get("trade_status"));
    String verifyResult = alipayClient.verifyNotifySignature(payload) ? "SIGN_OK" : "SIGN_FAIL";
    String processResult = "REJECTED";

    if (!"SIGN_OK".equals(verifyResult)) {
      persistCallbackAudit(orderNo, notifyId, tradeNo, payload, verifyResult, processResult);
      return new NotifyProcessResult(false, "invalid_sign");
    }
    if (isBlank(outTradeNo)) {
      persistCallbackAudit(orderNo, notifyId, tradeNo, payload, verifyResult, "ORDER_MISSING");
      return new NotifyProcessResult(false, "missing_out_trade_no");
    }
    if (!isBlank(alipay.appId()) && !alipay.appId().trim().equals(trimOrNull(payload.get("app_id")))) {
      persistCallbackAudit(orderNo, notifyId, tradeNo, payload, verifyResult, "APP_ID_MISMATCH");
      return new NotifyProcessResult(false, "app_id_mismatch");
    }
    if (!isBlank(alipay.sellerId())
        && !alipay.sellerId().trim().equals(trimOrNull(payload.get("seller_id")))) {
      persistCallbackAudit(orderNo, notifyId, tradeNo, payload, verifyResult, "SELLER_ID_MISMATCH");
      return new NotifyProcessResult(false, "seller_id_mismatch");
    }
    if (isBlank(tradeStatus) || !SUCCESS_TRADE_STATUS.contains(tradeStatus)) {
      persistCallbackAudit(orderNo, notifyId, tradeNo, payload, verifyResult, "TRADE_NOT_SUCCESS");
      return new NotifyProcessResult(false, "trade_not_success");
    }

    long paidAmountFen;
    try {
      paidAmountFen = parseAmountFen(payload.get("total_amount"));
    } catch (Exception exception) {
      persistCallbackAudit(orderNo, notifyId, tradeNo, payload, verifyResult, "AMOUNT_INVALID");
      return new NotifyProcessResult(false, "invalid_total_amount");
    }

    try {
      processResult = databaseManager.inTransaction(connection -> {
        PaymentOrderView locked = readOrderByOutTradeNo(connection, outTradeNo, true);
        if (locked == null) {
          return "ORDER_NOT_FOUND";
        }
        if (STATUS_PAID.equalsIgnoreCase(locked.status())) {
          return "ALREADY_PAID";
        }
        if (STATUS_CLOSED.equalsIgnoreCase(locked.status())
            || STATUS_FAILED.equalsIgnoreCase(locked.status())) {
          return "ORDER_CLOSED";
        }
        if (locked.amountCnyFen() != paidAmountFen) {
          markOrderStatus(connection, locked.orderNo(), STATUS_FAILED);
          return "AMOUNT_MISMATCH";
        }
        walletService.applyDelta(
            connection,
            locked.userId(),
            CurrencyType.SHOP_COIN,
            locked.shopCoinAmount(),
            BIZ_TYPE_PAYMENT_ALIPAY_IN,
            locked.orderNo(),
            false);
        markOrderPaid(connection, locked.orderNo(), tradeNo, TimeSupport.utcNow());
        return "PAID";
      });
    } catch (ServiceException exception) {
      processResult = "LEDGER_FAILED";
      persistCallbackAudit(orderNo, notifyId, tradeNo, payload, verifyResult, processResult);
      throw exception;
    }

    persistCallbackAudit(orderNo, notifyId, tradeNo, payload, verifyResult, processResult);
    boolean success = "PAID".equals(processResult) || "ALREADY_PAID".equals(processResult);
    return new NotifyProcessResult(success, success ? "success" : processResult.toLowerCase(Locale.ROOT));
  }

  int closeExpiredOrders() {
    return databaseManager.withConnection(connection -> closeExpiredOrders(connection, TimeSupport.utcNow()));
  }

  private String buildPayUrl(
      PluginSettings.AlipaySettings alipay,
      String outTradeNo,
      long amountFen,
      String subject,
      String body,
      int expireMinutes,
      String returnUrl) {
    AlipayClient client = new AlipayClient(alipay);
    AlipayClient.PagePayRequest request = new AlipayClient.PagePayRequest(
        outTradeNo,
        amountFen,
        subject,
        body,
        expireMinutes,
        alipay.notifyUrl(),
        isBlank(returnUrl) ? alipay.returnUrl() : returnUrl);
    return client.createPagePayUrl(request);
  }

  private PluginSettings.AlipaySettings requireAlipayEnabled() {
    PluginSettings.PaymentSettings payment = settingsSupplier.get().paymentSettings();
    if (payment == null || payment.alipaySettings() == null) {
      throw new ServiceException("feature_disabled", "Payment settings are unavailable");
    }
    PluginSettings.AlipaySettings alipay = payment.alipaySettings();
    if (!alipay.enabled()) {
      throw new ServiceException("feature_disabled", "Alipay recharge is disabled");
    }
    if (alipay.shopcoinUnitPriceFen() <= 0L) {
      throw new ServiceException("bad_request", "Invalid shopcoin unit price configuration");
    }
    return alipay;
  }

  private void validateRechargeAmount(long shopCoinAmount, PluginSettings.AlipaySettings alipay) {
    if (shopCoinAmount <= 0L) {
      throw new ServiceException("invalid_amount", "Recharge amount must be positive");
    }
    if (shopCoinAmount < alipay.minRechargeShopcoin()
        || shopCoinAmount > alipay.maxRechargeShopcoin()) {
      throw new ServiceException("invalid_amount", "Recharge amount is out of allowed range");
    }
  }

  private String sanitizeReturnUrl(String raw) {
    if (isBlank(raw)) {
      return null;
    }
    String value = raw.trim();
    if (value.length() > 255) {
      throw new ServiceException("bad_request", "clientReturnUrl is too long");
    }
    if (!value.startsWith("http://") && !value.startsWith("https://")) {
      throw new ServiceException("bad_request", "clientReturnUrl must be absolute URL");
    }
    return value;
  }

  private int normalizedExpireMinutes(int raw) {
    return Math.max(5, Math.min(raw <= 0 ? 20 : raw, 120));
  }

  private long safeMultiply(long left, long right) {
    try {
      return Math.multiplyExact(left, right);
    } catch (ArithmeticException exception) {
      throw new ServiceException("invalid_amount", "Recharge amount is too large");
    }
  }

  private void enforceCreateRateLimit(
      Connection connection,
      long userId,
      LocalDateTime now,
      int maxCreatePerMinute) throws SQLException {
    if (maxCreatePerMinute <= 0) {
      return;
    }
    String sql = """
        SELECT COUNT(*)
        FROM payment_orders
        WHERE user_id = ? AND created_at >= ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      statement.setTimestamp(2, Timestamp.valueOf(now.minusMinutes(1)));
      try (ResultSet resultSet = statement.executeQuery()) {
        if (resultSet.next() && resultSet.getInt(1) >= maxCreatePerMinute) {
          throw new ServiceException("rate_limited", "Too many payment orders in one minute");
        }
      }
    }
  }

  private boolean insertPaymentOrder(
      Connection connection,
      String orderNo,
      String outTradeNo,
      long userId,
      long shopCoinAmount,
      long amountCnyFen,
      String subject,
      String body,
      LocalDateTime expireAt,
      String payUrl,
      String clientIp,
      String userAgent) throws SQLException {
    String sql = """
        INSERT INTO payment_orders (
          order_no,
          user_id,
          provider,
          status,
          shop_coin_amount,
          amount_cny_fen,
          subject,
          body,
          out_trade_no,
          expire_at,
          extra_json
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    JsonObject extra = new JsonObject();
    if (!isBlank(payUrl)) {
      extra.addProperty("payUrl", payUrl);
    }
    if (!isBlank(clientIp)) {
      extra.addProperty("clientIp", clientIp.trim());
    }
    if (!isBlank(userAgent)) {
      extra.addProperty("userAgent", userAgent.trim());
    }

    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, orderNo);
      statement.setLong(2, userId);
      statement.setString(3, PROVIDER_ALIPAY);
      statement.setString(4, STATUS_CREATED);
      statement.setLong(5, shopCoinAmount);
      statement.setLong(6, amountCnyFen);
      statement.setString(7, subject);
      statement.setString(8, body);
      statement.setString(9, outTradeNo);
      statement.setTimestamp(10, Timestamp.valueOf(expireAt));
      statement.setString(11, extra.toString());
      statement.executeUpdate();
      return true;
    } catch (SQLException exception) {
      if ("23000".equals(exception.getSQLState())) {
        return false;
      }
      throw exception;
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Lock clause is selected from a fixed boolean branch")
  private PaymentOrderView readOrderByOutTradeNo(
      Connection connection,
      String outTradeNo,
      boolean forUpdate) throws SQLException {
    String lock = forUpdate ? " FOR UPDATE" : "";
    String sql = """
        SELECT user_id, order_no, out_trade_no, status, shop_coin_amount, amount_cny_fen,
               trade_no, notify_time, paid_at, expire_at, created_at, updated_at
        FROM payment_orders
        WHERE out_trade_no = ?
        """ + lock;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, outTradeNo);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return mapOrderRow(resultSet, resultSet.getLong("user_id"));
      }
    }
  }

  private PaymentOrderView readOrderByOrderNo(Connection connection, String orderNo, long userId)
      throws SQLException {
    String sql = """
        SELECT user_id, order_no, out_trade_no, status, shop_coin_amount, amount_cny_fen,
               trade_no, notify_time, paid_at, expire_at, created_at, updated_at
        FROM payment_orders
        WHERE order_no = ? AND user_id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, orderNo);
      statement.setLong(2, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return mapOrderRow(resultSet, userId);
      }
    }
  }

  private PaymentOrderView mapOrderRow(ResultSet resultSet, long userId) throws SQLException {
    return new PaymentOrderView(
        userId,
        resultSet.getString("order_no"),
        resultSet.getString("out_trade_no"),
        resultSet.getString("status"),
        resultSet.getLong("shop_coin_amount"),
        resultSet.getLong("amount_cny_fen"),
        resultSet.getString("trade_no"),
        toLocalDateTime(resultSet.getTimestamp("notify_time")),
        toLocalDateTime(resultSet.getTimestamp("paid_at")),
        toLocalDateTime(resultSet.getTimestamp("expire_at")),
        toLocalDateTime(resultSet.getTimestamp("created_at")),
        toLocalDateTime(resultSet.getTimestamp("updated_at")));
  }

  private void markOrderPaid(
      Connection connection,
      String orderNo,
      String tradeNo,
      LocalDateTime now) throws SQLException {
    String sql = """
        UPDATE payment_orders
        SET status = ?, trade_no = ?, notify_time = ?, paid_at = ?, updated_at = CURRENT_TIMESTAMP
        WHERE order_no = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, STATUS_PAID);
      statement.setString(2, tradeNo);
      statement.setTimestamp(3, Timestamp.valueOf(now));
      statement.setTimestamp(4, Timestamp.valueOf(now));
      statement.setString(5, orderNo);
      statement.executeUpdate();
    }
  }

  private void markOrderStatus(Connection connection, String orderNo, String status) throws SQLException {
    String sql = """
        UPDATE payment_orders
        SET status = ?, updated_at = CURRENT_TIMESTAMP
        WHERE order_no = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, status);
      statement.setString(2, orderNo);
      statement.executeUpdate();
    }
  }

  private void persistCallbackAudit(
      String orderNo,
      String notifyId,
      String tradeNo,
      Map<String, String> payload,
      String verifyResult,
      String processResult) {
    databaseManager.withConnection(connection -> {
      String sql = """
          INSERT INTO payment_callbacks (
            order_no,
            provider,
            notify_id,
            trade_no,
            payload_json,
            verify_result,
            process_result
          ) VALUES (?, ?, ?, ?, ?, ?, ?)
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, isBlank(orderNo) ? "-" : orderNo);
        statement.setString(2, PROVIDER_ALIPAY);
        statement.setString(3, notifyId);
        statement.setString(4, tradeNo);
        statement.setString(5, gson.toJson(payload));
        statement.setString(6, verifyResult);
        statement.setString(7, processResult);
        statement.executeUpdate();
      }
      return null;
    });
  }

  private int closeExpiredOrders(Connection connection, LocalDateTime now) throws SQLException {
    String sql = """
        UPDATE payment_orders
        SET status = ?, updated_at = CURRENT_TIMESTAMP
        WHERE expire_at < ?
          AND status IN (?, ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, STATUS_CLOSED);
      statement.setTimestamp(2, Timestamp.valueOf(now));
      statement.setString(3, STATUS_CREATED);
      statement.setString(4, STATUS_PAYING);
      return statement.executeUpdate();
    }
  }

  private long parseAmountFen(String raw) {
    BigDecimal amount = new BigDecimal(String.valueOf(raw == null ? "" : raw).trim());
    BigDecimal fen = amount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP);
    return fen.longValueExact();
  }

  private LocalDateTime toLocalDateTime(Timestamp value) {
    return value == null ? null : value.toLocalDateTime();
  }

  private String normalizeOrderNo(String orderNo) {
    String normalized = trimOrNull(orderNo);
    if (normalized == null || normalized.length() > 64) {
      throw new ServiceException("bad_request", "Invalid orderNo");
    }
    return normalized;
  }

  private String newOrderNo(LocalDateTime nowUtc) {
    String date = TimeSupport.utcToBusinessOffset(nowUtc, settingsSupplier.get().timeZone())
        .toLocalDate()
        .format(ORDER_DATE);
    String random = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    return "RCG-" + date + "-" + random;
  }

  private String trimOrNull(String text) {
    if (text == null) {
      return null;
    }
    String trimmed = text.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private boolean isBlank(String text) {
    return text == null || text.isBlank();
  }

  record CreateOrderResult(
      String orderNo,
      String outTradeNo,
      long shopCoinAmount,
      long amountCnyFen,
      String status,
      String payUrl,
      String qrCode,
      LocalDateTime expireAt) {
  }

  record NotifyProcessResult(boolean success, String responseText) {
  }

  record PaymentOrderView(
      long userId,
      String orderNo,
      String outTradeNo,
      String status,
      long shopCoinAmount,
      long amountCnyFen,
      String tradeNo,
      LocalDateTime notifyTime,
      LocalDateTime paidAt,
      LocalDateTime expireAt,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
  }
}
