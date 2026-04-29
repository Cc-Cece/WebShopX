package com.webshopx;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class AlipayClient {
  private static final DateTimeFormatter ALIPAY_TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final String PRODUCT_CODE_PAGE_PAY = "FAST_INSTANT_TRADE_PAY";

  private final PluginSettings.AlipaySettings settings;

  AlipayClient(PluginSettings.AlipaySettings settings) {
    this.settings = settings;
  }

  boolean canCreatePayUrl() {
    if (settings == null) {
      return false;
    }
    return !isBlank(settings.appId())
        && !isBlank(settings.merchantPrivateKey())
        && !isBlank(settings.gatewayUrl());
  }

  boolean canVerifyNotify() {
    if (settings == null) {
      return false;
    }
    return !isBlank(settings.alipayPublicKey());
  }

  String createPagePayUrl(PagePayRequest request) {
    if (!canCreatePayUrl()) {
      throw new ServiceException("payment_unavailable", "Alipay pay URL is not available");
    }
    String charset = normalizeCharset(settings.charset());
    Map<String, String> params = new LinkedHashMap<>();
    params.put("app_id", settings.appId().trim());
    params.put("method", "alipay.trade.page.pay");
    params.put("format", "JSON");
    params.put("charset", charset);
    params.put("sign_type", normalizeSignType(settings.signType()));
    params.put("timestamp", ALIPAY_TIMESTAMP_FORMAT.format(LocalDateTime.now()));
    params.put("version", "1.0");
    params.put("notify_url", chooseNotifyUrl(request.notifyUrl()));
    String returnUrl = chooseReturnUrl(request.returnUrl());
    if (returnUrl != null) {
      params.put("return_url", returnUrl);
    }
    params.put("biz_content", buildBizContent(request));
    params.put("sign", sign(params, charset));
    return settings.gatewayUrl().trim() + "?" + toQueryString(params, charset);
  }

  boolean verifyNotifySignature(Map<String, String> payload) {
    if (payload == null || payload.isEmpty() || !canVerifyNotify()) {
      return false;
    }
    String signatureBase64 = payload.get("sign");
    if (isBlank(signatureBase64)) {
      return false;
    }

    String charset = normalizeCharset(settings.charset());
    String signContent = buildSignContent(payload, true);
    if (signContent.isBlank()) {
      return false;
    }

    try {
      String signType = normalizeSignType(payload.get("sign_type"));
      String algorithm = signAlgorithm(signType);
      Signature signature = Signature.getInstance(algorithm);
      signature.initVerify(readPublicKey(settings.alipayPublicKey()));
      signature.update(signContent.getBytes(Charset.forName(charset)));
      byte[] decoded = Base64.getDecoder().decode(signatureBase64.trim());
      return signature.verify(decoded);
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      return false;
    }
  }

  private String buildBizContent(PagePayRequest request) {
    JsonObject biz = new JsonObject();
    biz.addProperty("out_trade_no", request.outTradeNo());
    biz.addProperty("total_amount", fenToAmountYuanText(request.amountFen()));
    biz.addProperty("subject", request.subject());
    if (!isBlank(request.body())) {
      biz.addProperty("body", request.body().trim());
    }
    biz.addProperty("product_code", PRODUCT_CODE_PAGE_PAY);
    if (request.expireMinutes() > 0) {
      biz.addProperty("timeout_express", request.expireMinutes() + "m");
    }
    return biz.toString();
  }

  private String chooseNotifyUrl(String override) {
    String chosen = isBlank(override) ? settings.notifyUrl() : override;
    if (isBlank(chosen)) {
      throw new ServiceException("payment_unavailable", "Alipay notify URL is not configured");
    }
    return chosen.trim();
  }

  private String chooseReturnUrl(String override) {
    String chosen = isBlank(override) ? settings.returnUrl() : override;
    if (isBlank(chosen)) {
      return null;
    }
    return chosen.trim();
  }

  private String sign(Map<String, String> params, String charset) {
    try {
      String signContent = buildSignContent(params, false);
      String signType = normalizeSignType(settings.signType());
      String algorithm = signAlgorithm(signType);
      Signature signature = Signature.getInstance(algorithm);
      signature.initSign(readPrivateKey(settings.merchantPrivateKey()));
      signature.update(signContent.getBytes(Charset.forName(charset)));
      return Base64.getEncoder().encodeToString(signature.sign());
    } catch (GeneralSecurityException exception) {
      throw new ServiceException("payment_unavailable", "Failed to sign Alipay request");
    }
  }

  private String buildSignContent(Map<String, String> params, boolean forVerify) {
    List<String> keys = new ArrayList<>(params.keySet());
    keys.sort(String::compareTo);
    List<String> pairs = new ArrayList<>();
    for (String key : keys) {
      if (key == null) {
        continue;
      }
      String normalizedKey = key.trim();
      if (normalizedKey.isEmpty()) {
        continue;
      }
      if ("sign".equalsIgnoreCase(normalizedKey)) {
        continue;
      }
      if (forVerify && "sign_type".equalsIgnoreCase(normalizedKey)) {
        continue;
      }
      String value = params.get(key);
      if (isBlank(value)) {
        continue;
      }
      pairs.add(normalizedKey + "=" + value.trim());
    }
    return String.join("&", pairs);
  }

  private String toQueryString(Map<String, String> params, String charset) {
    List<String> encodedPairs = new ArrayList<>();
    for (Map.Entry<String, String> entry : params.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        continue;
      }
      String key = URLEncoder.encode(entry.getKey(), Charset.forName(charset));
      String value = URLEncoder.encode(entry.getValue(), Charset.forName(charset));
      encodedPairs.add(key + "=" + value);
    }
    return String.join("&", encodedPairs);
  }

  private String fenToAmountYuanText(long amountFen) {
    BigDecimal fen = BigDecimal.valueOf(amountFen);
    BigDecimal yuan = fen.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    return yuan.toPlainString();
  }

  private String signAlgorithm(String signType) {
    if ("RSA".equalsIgnoreCase(signType)) {
      return "SHA1withRSA";
    }
    return "SHA256withRSA";
  }

  private String normalizeCharset(String raw) {
    if (isBlank(raw)) {
      return StandardCharsets.UTF_8.name().toLowerCase(Locale.ROOT);
    }
    return raw.trim().toLowerCase(Locale.ROOT);
  }

  private String normalizeSignType(String raw) {
    if (isBlank(raw)) {
      return "RSA2";
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    return "RSA".equals(normalized) ? "RSA" : "RSA2";
  }

  private PrivateKey readPrivateKey(String rawPrivateKey) throws GeneralSecurityException {
    byte[] decoded = Base64.getDecoder().decode(sanitizePem(rawPrivateKey));
    PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
    return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
  }

  private PublicKey readPublicKey(String rawPublicKey) throws GeneralSecurityException {
    byte[] decoded = Base64.getDecoder().decode(sanitizePem(rawPublicKey));
    X509EncodedKeySpec keySpec = new X509EncodedKeySpec(decoded);
    return KeyFactory.getInstance("RSA").generatePublic(keySpec);
  }

  private String sanitizePem(String keyText) {
    String safe = String.valueOf(keyText == null ? "" : keyText);
    return safe
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replace("-----BEGIN RSA PRIVATE KEY-----", "")
        .replace("-----END RSA PRIVATE KEY-----", "")
        .replace("-----BEGIN RSA PUBLIC KEY-----", "")
        .replace("-----END RSA PUBLIC KEY-----", "")
        .replaceAll("\\s+", "");
  }

  private boolean isBlank(String text) {
    return text == null || text.isBlank();
  }

  record PagePayRequest(
      String outTradeNo,
      long amountFen,
      String subject,
      String body,
      int expireMinutes,
      String notifyUrl,
      String returnUrl) {
  }
}
