package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SharedRouteContractTest {
  @Test
  void freezesTheCompleteProductRouteInventory() {
    assertEquals(171, SharedRouteContract.routes().size());
    assertTrue(SharedRouteContract.routes().contains("/api/orders/refund"));
    assertTrue(SharedRouteContract.routes().contains("/api/market/bid"));
    assertTrue(SharedRouteContract.routes().contains("/api/admin/visual-packs/upload"));
    assertTrue(SharedRouteContract.routes().contains("/api/checkout/submit"));
    assertTrue(SharedRouteContract.routes().contains("/api/admin/promotions/publish"));
    assertTrue(SharedRouteContract.routes().contains("/api/market/supply/reconcile"));
    assertTrue(SharedRouteContract.routes().contains("/api/admin/market/supply/unknown"));
    assertThrows(
        UnsupportedOperationException.class, () -> SharedRouteContract.routes().add("/undeclared"));
    assertThrows(
        IllegalArgumentException.class, () -> SharedRouteContract.requireDeclared("/undeclared"));
  }

  @Test
  void rejectsAdaptersThatRelyOnTheStaticRootToMaskMissingApiRoutes() {
    assertThrows(
        IllegalStateException.class,
        () -> SharedRouteContract.requireComplete("broken", Set.of("/"), Set.of()));
  }

  @Test
  void permitsAnExplicitStaticRootOmissionInExternalFrontendMode() {
    Set<String> externalRoutes = new LinkedHashSet<>(SharedRouteContract.routes());
    externalRoutes.remove("/");
    SharedRouteContract.requireComplete("external", externalRoutes, Set.of("/"));
  }
}
