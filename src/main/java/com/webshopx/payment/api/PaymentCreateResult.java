package com.webshopx.payment.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class PaymentCreateResult {
  private boolean success;
  private String merchantOrderId;
  private String providerOrderId;
  private PaymentStatus status = PaymentStatus.UNKNOWN;
  private PaymentMethod method;
  private String methodCode;
  private String payUrl;
  private String qrCodeUrl;
  private Instant expiresAt;
  private String errorCode;
  private String message;
  private Map<String, String> extra = new LinkedHashMap<>();

  public boolean isSuccess() {
    return success;
  }

  public void setSuccess(boolean success) {
    this.success = success;
  }

  public String getMerchantOrderId() {
    return merchantOrderId;
  }

  public void setMerchantOrderId(String merchantOrderId) {
    this.merchantOrderId = merchantOrderId;
  }

  public String getProviderOrderId() {
    return providerOrderId;
  }

  public void setProviderOrderId(String providerOrderId) {
    this.providerOrderId = providerOrderId;
  }

  public PaymentStatus getStatus() {
    return status;
  }

  public void setStatus(PaymentStatus status) {
    this.status = status == null ? PaymentStatus.UNKNOWN : status;
  }

  public PaymentMethod getMethod() {
    return method;
  }

  public void setMethod(PaymentMethod method) {
    this.method = method;
  }

  public String getMethodCode() {
    return methodCode;
  }

  public void setMethodCode(String methodCode) {
    this.methodCode = methodCode;
  }

  public String getPayUrl() {
    return payUrl;
  }

  public void setPayUrl(String payUrl) {
    this.payUrl = payUrl;
  }

  public String getQrCodeUrl() {
    return qrCodeUrl;
  }

  public void setQrCodeUrl(String qrCodeUrl) {
    this.qrCodeUrl = qrCodeUrl;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public void setErrorCode(String errorCode) {
    this.errorCode = errorCode;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public Map<String, String> getExtra() {
    return extra;
  }

  public void setExtra(Map<String, String> extra) {
    this.extra = extra == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extra);
  }
}
