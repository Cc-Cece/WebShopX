package com.webshopx.payment.api;

import java.util.Set;

/**
 * Host-neutral payment service contract. Optional features are exposed through separate capability
 * interfaces such as {@link PaymentConfigurable}; implementing the payment API never requires
 * exposing provider configuration.
 */
public interface WebShopXPaymentApi {
  String providerId();

  String displayName();

  Set<PaymentMethod> supportedMethods();

  default Set<String> supportedCurrencies() {
    return Set.of();
  }

  default RechargeRatePolicy rechargeRatePolicy() {
    return RechargeRatePolicy.webShopXManaged();
  }

  PaymentCreateResult createPayment(PaymentCreateRequest request);

  PaymentQueryResult queryPayment(PaymentQueryRequest request);

  default void cancelPayment(PaymentQueryRequest request) {
    // Optional provider hook. Existing providers remain source and binary compatible.
  }

  void registerListener(String consumerId, PaymentListener listener);

  void unregisterListener(String consumerId);
}
