package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.webshopx.platform.CompatibilityDomain;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ItemCompatibilityPolicyTest {
  @Test
  void permitsOnlyDeclaredVanillaAllowlistAcrossDomains() {
    CompatibilityDomain source = new CompatibilityDomain("paper", "paper", "1.20.1", 1, "vanilla");
    CompatibilityDomain target = new CompatibilityDomain("fabric", "fabric", "26.2", 1, "vanilla");
    ItemEnvelopeService envelopes = new ItemEnvelopeService(Clock.systemUTC(), Set.of("paper-pdc"));
    ItemCompatibilityPolicy policy = new ItemCompatibilityPolicy(Set.of("minecraft:stone"));

    var portable = envelopes.create("paper-pdc", 1, source, "minecraft:stone", 1,
        new byte[]{1}, Map.of("portable", "true", "nestedDepth", "1"));
    assertEquals(ItemCompatibilityPolicy.Decision.PORTABLE_CONVERSION_REQUIRED,
        policy.route(portable, target));

    var modded = envelopes.create("paper-pdc", 1, source, "example:machine", 1,
        new byte[]{2}, Map.of("portable", "true"));
    assertEquals(ItemCompatibilityPolicy.Decision.REJECT_MOD_DOMAIN, policy.route(modded, target));

    var deep = envelopes.create("paper-pdc", 1, source, "minecraft:stone", 1,
        new byte[]{3}, Map.of("portable", "true", "nestedDepth", "3"));
    assertEquals(ItemCompatibilityPolicy.Decision.REJECT_NESTING_LIMIT, policy.route(deep, target));
  }
}
