package com.webshopx.payment.api;

import java.util.List;

public final class PaymentConfigSection {
  private final String id; private final String title; private final String description;
  private final String parentId;
  private final List<PaymentConfigField> fields;
  public PaymentConfigSection(String id, String title, String description, List<PaymentConfigField> fields) {
    this(id, null, title, description, fields);
  }
  public PaymentConfigSection(String id, String parentId, String title, String description,
      List<PaymentConfigField> fields) {
    this.id=id; this.parentId=parentId; this.title=title; this.description=description;
    this.fields=fields == null ? List.of() : List.copyOf(fields);
  }
  public String id(){return id;} public String title(){return title;}
  public String parentId(){return parentId;}
  public String description(){return description;} public List<PaymentConfigField> fields(){return fields;}
}
