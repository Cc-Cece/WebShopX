package com.webshopx.payment.api;

public final class PaymentConfigOption {
  private final String value;
  private final String label;
  public PaymentConfigOption(String value, String label) { this.value = value; this.label = label; }
  public String value() { return value; }
  public String label() { return label; }
}
