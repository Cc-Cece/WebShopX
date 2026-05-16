package com.webshopx.platform;

public interface EconomyGateway {
  String providerName();

  long readBalance(String accountName);

  long applyDelta(String accountName, long delta, boolean enforceBalance);

  boolean available();
}
