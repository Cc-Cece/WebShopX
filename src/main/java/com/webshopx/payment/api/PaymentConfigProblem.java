package com.webshopx.payment.api;

public final class PaymentConfigProblem {
  private final String field; private final String code; private final String message;
  public PaymentConfigProblem(String field,String code,String message){this.field=field;this.code=code;this.message=message;}
  public String field(){return field;} public String code(){return code;} public String message(){return message;}
}
