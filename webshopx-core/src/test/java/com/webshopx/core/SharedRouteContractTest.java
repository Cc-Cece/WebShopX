package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SharedRouteContractTest {
  @Test void freezesTheCompleteProductRouteInventory() {
    assertEquals(166, SharedRouteContract.routes().size());
    assertTrue(SharedRouteContract.routes().contains("/api/orders/refund"));
    assertTrue(SharedRouteContract.routes().contains("/api/market/bid"));
    assertTrue(SharedRouteContract.routes().contains("/api/admin/visual-packs/upload"));
    assertTrue(SharedRouteContract.routes().contains("/api/checkout/submit"));
    assertTrue(SharedRouteContract.routes().contains("/api/admin/promotions/publish"));
    assertThrows(UnsupportedOperationException.class,
        () -> SharedRouteContract.routes().add("/undeclared"));
    assertThrows(IllegalArgumentException.class,
        () -> SharedRouteContract.requireDeclared("/undeclared"));
  }
}
