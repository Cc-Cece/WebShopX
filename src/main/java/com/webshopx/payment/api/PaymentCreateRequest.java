package com.webshopx.payment.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class PaymentCreateRequest {
  private String merchantOrderId;
  private String userId;
  private UUID playerUuid;
  private long amountMinor;
  private String currency;
  private String subject;
  private String description;
  private PaymentMethod preferredMethod;
  private String methodCode;
  private String returnUrl;
  private String notifyUrl;
  private Instant expiresAt;
  private Map<String, String> metadata = new LinkedHashMap<>();

  public String getMerchantOrderId() {
    return merchantOrderId;
  }

  public void setMerchantOrderId(String merchantOrderId) {
    this.merchantOrderId = merchantOrderId;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public UUID getPlayerUuid() {
    return playerUuid;
  }

  public void setPlayerUuid(UUID playerUuid) {
    this.playerUuid = playerUuid;
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

  public String getSubject() {
    return subject;
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public PaymentMethod getPreferredMethod() {
    return preferredMethod;
  }

  public void setPreferredMethod(PaymentMethod preferredMethod) {
    this.preferredMethod = preferredMethod;
  }

  public String getMethodCode() {
    return methodCode;
  }

  public void setMethodCode(String methodCode) {
    this.methodCode = methodCode;
  }

  public String getReturnUrl() {
    return returnUrl;
  }

  public void setReturnUrl(String returnUrl) {
    this.returnUrl = returnUrl;
  }

  public String getNotifyUrl() {
    return notifyUrl;
  }

  public void setNotifyUrl(String notifyUrl) {
    this.notifyUrl = notifyUrl;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public Map<String, String> getMetadata() {
    return metadata;
  }

  public void setMetadata(Map<String, String> metadata) {
    this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
  }
}
