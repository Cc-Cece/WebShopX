package com.webshopx.payment.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class PaymentNotify {
  private String merchantOrderId;
  private String providerOrderId;
  private PaymentStatus status = PaymentStatus.UNKNOWN;
  private long amountMinor;
  private String currency;
  private PaymentMethod method;
  private String methodCode;
  private Instant paidAt;
  private String rawEventId;
  private Map<String, String> extra = new LinkedHashMap<>();

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

  public long getAmountMinor() {
    return amountMinor;
  }

  public void setAmountMinor(long amountMinor) {
    this.amountMinor = amountMinor;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
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

  public Instant getPaidAt() {
    return paidAt;
  }

  public void setPaidAt(Instant paidAt) {
    this.paidAt = paidAt;
  }

  public String getRawEventId() {
    return rawEventId;
  }

  public void setRawEventId(String rawEventId) {
    this.rawEventId = rawEventId;
  }

  public Map<String, String> getExtra() {
    return extra;
  }

  public void setExtra(Map<String, String> extra) {
    this.extra = extra == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extra);
  }
}
