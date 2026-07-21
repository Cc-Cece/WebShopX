package com.webshopx.payment.api;

import java.util.List;

public final class PaymentConfigDescriptor {
  private final int schemaVersion; private final List<PaymentConfigSection> sections;
  public PaymentConfigDescriptor(int schemaVersion, List<PaymentConfigSection> sections) {
    this.schemaVersion=schemaVersion; this.sections=sections == null ? List.of() : List.copyOf(sections);
  }
  public int schemaVersion(){return schemaVersion;} public List<PaymentConfigSection> sections(){return sections;}
}
