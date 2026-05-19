package com.webshopx.payment.api;

public class PaymentNotifyResult {
  private boolean success;
  private String code;
  private String message;

  public PaymentNotifyResult() {
  }

  public PaymentNotifyResult(boolean success, String code, String message) {
    this.success = success;
    this.code = code;
    this.message = message;
  }

  public static PaymentNotifyResult ok(String message) {
    return new PaymentNotifyResult(true, "OK", message);
  }

  public static PaymentNotifyResult fail(String code, String message) {
    return new PaymentNotifyResult(false, code, message);
  }

  public boolean isSuccess() {
    return success;
  }

  public void setSuccess(boolean success) {
    this.success = success;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }
}
