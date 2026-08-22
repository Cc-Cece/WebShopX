package com.webshopx.core;

import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformResult;
import java.util.Collection;
import java.util.Comparator;

/** Selects only nodes that can decode the original item payload without lossy conversion. */
public final class CompatibilityRouter {
  public PlatformResult<Node> route(ItemEnvelope envelope, Collection<Node> nodes) {
    return nodes.stream()
        .filter(Node::ready)
        .filter(node -> envelope.compatibilityDomain().sameNativeDomain(node.domain()))
        .min(Comparator.comparingInt(Node::pendingDeliveries).thenComparing(Node::serverId))
        .<PlatformResult<Node>>map(PlatformResult::success)
        .orElseGet(() -> PlatformResult.rejected(
            "NO_COMPATIBLE_DELIVERY_NODE", "delivery.no_compatible_node"));
  }

  public record Node(String serverId, CompatibilityDomain domain, boolean ready,
                     int pendingDeliveries) {
    public Node {
      if (serverId == null || serverId.isBlank()) throw new IllegalArgumentException("serverId");
      if (domain == null) throw new IllegalArgumentException("domain");
      if (pendingDeliveries < 0) throw new IllegalArgumentException("pendingDeliveries");
    }
  }
}
