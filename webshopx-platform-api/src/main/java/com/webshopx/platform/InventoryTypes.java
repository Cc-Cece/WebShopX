package com.webshopx.platform;

import java.util.List;
import java.util.UUID;

public final class InventoryTypes {
  private InventoryTypes() { }

  public record InventorySnapshot(UUID playerId, long version, int freeSlots, List<ItemEnvelope> items) {
    public InventorySnapshot { items = List.copyOf(items); }
  }

  public record InventoryMutation(
      String operationId, UUID playerId, long expectedVersion, List<ItemEnvelope> insertions,
      List<ItemEnvelope> removals) {
    public InventoryMutation {
      insertions = List.copyOf(insertions);
      removals = List.copyOf(removals);
    }
  }

  public record InventoryMutationResult(
      long newVersion, List<ItemEnvelope> inserted, List<ItemEnvelope> removed,
      List<ItemEnvelope> remainder) {
    public InventoryMutationResult {
      inserted = List.copyOf(inserted);
      removed = List.copyOf(removed);
      remainder = List.copyOf(remainder);
    }
  }
}
