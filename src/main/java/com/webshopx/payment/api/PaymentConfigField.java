package com.webshopx.payment.api;

import java.util.List;

public final class PaymentConfigField {
  private final String key; private final PaymentConfigFieldType type; private final String label;
  private final String description; private final PaymentConfigAccess access;
  private final PaymentConfigApplyMode applyMode; private final boolean required;
  private final Object defaultValue; private final Double minimum; private final Double maximum;
  private final List<PaymentConfigOption> options;
  public PaymentConfigField(String key, PaymentConfigFieldType type, String label, String description,
      PaymentConfigAccess access, PaymentConfigApplyMode applyMode, boolean required, Object defaultValue,
      Double minimum, Double maximum, List<PaymentConfigOption> options) {
    this.key=key; this.type=type; this.label=label; this.description=description; this.access=access;
    this.applyMode=applyMode; this.required=required; this.defaultValue=defaultValue;
    this.minimum=minimum; this.maximum=maximum;
    this.options=options == null ? List.of() : List.copyOf(options);
  }
  public String key(){return key;} public PaymentConfigFieldType type(){return type;}
  public String label(){return label;} public String description(){return description;}
  public PaymentConfigAccess access(){return access;} public PaymentConfigApplyMode applyMode(){return applyMode;}
  public boolean required(){return required;} public Object defaultValue(){return defaultValue;}
  public Double minimum(){return minimum;} public Double maximum(){return maximum;}
  public List<PaymentConfigOption> options(){return options;}
}
