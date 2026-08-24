package com.webshopx.platform;

import java.util.List;
import java.util.UUID;

public final class InventoryTypes {
  private InventoryTypes() {}

  public record InventorySnapshot(
      UUID playerId, long version, int freeSlots, List<ItemEnvelope> items) {
    public InventorySnapshot {
      items = List.copyOf(items);
    }
  }

  public record InventoryMutation(
      String operationId,
      UUID playerId,
      long expectedVersion,
      List<ItemEnvelope> insertions,
      List<InventoryRemoval> removals) {
    public InventoryMutation {
      insertions = List.copyOf(insertions);
      removals = List.copyOf(removals);
    }
  }

  /** Removes a quantity from the exact stack observed in a prior inventory snapshot. */
  public record InventoryRemoval(ItemEnvelope expectedStack, int quantity) {
    public InventoryRemoval {
      if (expectedStack == null) throw new IllegalArgumentException("expectedStack");
      if (quantity < 1 || quantity > expectedStack.count()) {
        throw new IllegalArgumentException("invalid removal quantity");
      }
    }
  }

  public record InventoryMutationResult(
      long newVersion,
      List<ItemEnvelope> inserted,
      List<ItemEnvelope> removed,
      List<ItemEnvelope> remainder) {
    public InventoryMutationResult {
      inserted = List.copyOf(inserted);
      removed = List.copyOf(removed);
      remainder = List.copyOf(remainder);
    }
  }
}
