package com.webshopx.payment.api;

public interface PaymentListener {
  PaymentNotifyResult onPaymentNotify(PaymentNotify notify);
}
