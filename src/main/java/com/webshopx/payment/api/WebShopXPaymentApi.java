package com.webshopx.payment.api;

import java.util.Set;

public interface WebShopXPaymentApi {
  String providerId();

  String displayName();

  Set<PaymentMethod> supportedMethods();

  default Set<String> supportedCurrencies() {
    return Set.of();
  }

  PaymentCreateResult createPayment(PaymentCreateRequest request);

  PaymentQueryResult queryPayment(PaymentQueryRequest request);

  void registerListener(String consumerId, PaymentListener listener);

  void unregisterListener(String consumerId);
}
