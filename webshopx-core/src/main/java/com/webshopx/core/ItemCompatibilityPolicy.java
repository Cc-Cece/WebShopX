package com.webshopx.core;

import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.ItemEnvelope;
import java.util.Objects;
import java.util.Set;

/** Explicit legacy/modern routing policy. It never rewrites an opaque payload. */
public final class ItemCompatibilityPolicy {
  private final Set<String> portableVanillaItems;

  public ItemCompatibilityPolicy(Set<String> portableVanillaItems) {
    this.portableVanillaItems = Set.copyOf(portableVanillaItems);
  }

  public Decision route(ItemEnvelope envelope, CompatibilityDomain target) {
    Objects.requireNonNull(envelope, "envelope");
    Objects.requireNonNull(target, "target");
    if (envelope.compatibilityDomain().sameNativeDomain(target)) return Decision.NATIVE;
    if (!envelope.registryId().startsWith("minecraft:")) return Decision.REJECT_MOD_DOMAIN;
    if (!portableVanillaItems.contains(envelope.registryId())) return Decision.REJECT_NOT_ALLOWLISTED;
    if (!"true".equals(envelope.summary().get("portable"))) return Decision.REJECT_NOT_DECLARED_PORTABLE;
    if (Integer.parseInt(envelope.summary().getOrDefault("nestedDepth", "0")) > 2) {
      return Decision.REJECT_NESTING_LIMIT;
    }
    return Decision.PORTABLE_CONVERSION_REQUIRED;
  }

  public enum Decision {
    NATIVE,
    PORTABLE_CONVERSION_REQUIRED,
    REJECT_MOD_DOMAIN,
    REJECT_NOT_ALLOWLISTED,
    REJECT_NOT_DECLARED_PORTABLE,
    REJECT_NESTING_LIMIT
  }
}
