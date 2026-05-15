package com.webshopx;

enum RechargeOrderStatus {
  PENDING,
  PAYING,
  PAID,
  FAILED,
  EXPIRED,
  CLOSED;

  boolean isTerminal() {
    return this == PAID || this == FAILED || this == EXPIRED || this == CLOSED;
  }

  boolean canReceiveProviderResult() {
    return this == PENDING || this == PAYING;
  }
}
