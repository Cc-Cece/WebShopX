package com.webshopx.payment.api;

public record RechargeRatePolicy(
    boolean editable,
    SettlementAmountMode settlementAmountMode,
    String configurationNotice) {

  public RechargeRatePolicy {
    settlementAmountMode = settlementAmountMode == null
        ? SettlementAmountMode.WEBSHOPX_CALCULATED
        : settlementAmountMode;
    configurationNotice = configurationNotice == null ? "" : configurationNotice.trim();
  }

  public static RechargeRatePolicy webShopXManaged() {
    return new RechargeRatePolicy(true, SettlementAmountMode.WEBSHOPX_CALCULATED, "");
  }
}
