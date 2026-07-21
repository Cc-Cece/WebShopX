package com.webshopx.payment.api;

import java.util.Map;
import java.util.Set;

/** Values exposed by the provider. Write-only secret values must never be included. */
public final class PaymentConfigSnapshot {
  private final Map<String,Object> values; private final Set<String> configuredSecrets;
  public PaymentConfigSnapshot(Map<String,Object> values, Set<String> configuredSecrets) {
    this.values=values == null ? Map.of() : Map.copyOf(values);
    this.configuredSecrets=configuredSecrets == null ? Set.of() : Set.copyOf(configuredSecrets);
  }
  public Map<String,Object> values(){return values;} public Set<String> configuredSecrets(){return configuredSecrets;}
}
