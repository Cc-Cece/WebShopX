package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderRegistryTest {
  @Test void choosesHighestAvailablePriorityThenStableIdentifier() {
    var selected = ProviderRegistry.select(List.of(
        new ProviderRegistry.Candidate<>("failed", 100, false, "failed-provider"),
        new ProviderRegistry.Candidate<>("zeta", 20, true, "zeta-provider"),
        new ProviderRegistry.Candidate<>("alpha", 20, true, "alpha-provider"),
        new ProviderRegistry.Candidate<>("fallback", 1, true, "fallback-provider")));
    assertEquals("alpha-provider", selected.orElseThrow());
  }

  @Test void missingOptionalProviderDoesNotInventFallback() {
    assertTrue(ProviderRegistry.select(List.of(
        new ProviderRegistry.Candidate<>("offline", 1, false, "provider"))).isEmpty());
  }
}
