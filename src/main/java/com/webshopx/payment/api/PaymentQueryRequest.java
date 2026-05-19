package com.webshopx.payment.api;

public class PaymentQueryRequest {
  private String merchantOrderId;
  private String providerOrderId;
  private PaymentMethod method = PaymentMethod.AUTO;
  private String methodCode;

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

  public PaymentMethod getMethod() {
    return method;
  }

  public void setMethod(PaymentMethod method) {
    this.method = method == null ? PaymentMethod.AUTO : method;
  }

  public String getMethodCode() {
    return methodCode;
  }

  public void setMethodCode(String methodCode) {
    this.methodCode = methodCode;
  }
}
