package com.webshopx.core;

import com.webshopx.platform.PlatformPorts;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Deterministic provider selection; failed optional providers do not disable unrelated features. */
public final class ProviderRegistry {
  private ProviderRegistry() { }

  public static <T> Optional<T> select(List<Candidate<T>> candidates) {
    Objects.requireNonNull(candidates, "candidates");
    return candidates.stream()
        .filter(Candidate::available)
        .sorted(Comparator.comparingInt(Candidate<T>::priority).reversed()
            .thenComparing(Candidate::id))
        .map(Candidate::provider)
        .findFirst();
  }

  public record Candidate<T>(String id, int priority, boolean available, T provider) {
    public Candidate {
      if (id == null || id.isBlank()) throw new IllegalArgumentException("id");
      Objects.requireNonNull(provider, "provider");
    }
  }

  public record Providers(
      Optional<PlatformPorts.PermissionProvider> permissions,
      Optional<PlatformPorts.EconomyProvider> economy) {
    public Providers {
      Objects.requireNonNull(permissions, "permissions");
      Objects.requireNonNull(economy, "economy");
    }
  }
}
