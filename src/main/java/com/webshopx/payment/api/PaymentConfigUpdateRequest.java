package com.webshopx.payment.api;

import java.util.Map;
import java.util.Set;

/** A partial update. Omitted fields are unchanged; secrets are cleared only when explicitly listed. */
public final class PaymentConfigUpdateRequest {
  private final Map<String,Object> changes; private final Set<String> clearedSecrets;
  public PaymentConfigUpdateRequest(Map<String,Object> changes, Set<String> clearedSecrets) {
    this.changes=changes == null ? Map.of() : Map.copyOf(changes);
    this.clearedSecrets=clearedSecrets == null ? Set.of() : Set.copyOf(clearedSecrets);
  }
  public Map<String,Object> changes(){return changes;} public Set<String> clearedSecrets(){return clearedSecrets;}
}
