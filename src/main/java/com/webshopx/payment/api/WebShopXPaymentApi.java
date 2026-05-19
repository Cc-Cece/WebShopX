package com.webshopx.payment.api;

import java.util.Set;

public interface WebShopXPaymentApi {
  String providerId();

  String displayName();

  Set<PaymentMethod> supportedMethods();

  PaymentCreateResult createPayment(PaymentCreateRequest request);

  PaymentQueryResult queryPayment(PaymentQueryRequest request);

  void registerListener(String consumerId, PaymentListener listener);

  void unregisterListener(String consumerId);
}
