package com.webshopx.payment.api;

import java.util.List;

public final class PaymentConfigUpdateResult {
  private final PaymentConfigUpdateStatus status; private final String message;
  private final List<PaymentConfigProblem> problems;
  public PaymentConfigUpdateResult(PaymentConfigUpdateStatus status,String message,List<PaymentConfigProblem> problems) {
    this.status=status; this.message=message; this.problems=problems == null ? List.of() : List.copyOf(problems);
  }
  public PaymentConfigUpdateStatus status(){return status;} public String message(){return message;}
  public List<PaymentConfigProblem> problems(){return problems;}

  public static PaymentConfigUpdateResult applied(String message) {
    return new PaymentConfigUpdateResult(PaymentConfigUpdateStatus.APPLIED, message, List.of());
  }

  public static PaymentConfigUpdateResult rejected(String message, List<PaymentConfigProblem> problems) {
    return new PaymentConfigUpdateResult(PaymentConfigUpdateStatus.REJECTED, message, problems);
  }
}
