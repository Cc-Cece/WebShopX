package com.webshopx.testkit;

import com.webshopx.platform.InventoryTypes.InventoryMutation;
import com.webshopx.platform.InventoryTypes.InventoryMutationResult;
import com.webshopx.platform.InventoryTypes.InventoryRemoval;
import com.webshopx.platform.InventoryTypes.InventorySnapshot;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformPorts;
import com.webshopx.platform.PlatformResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Reference implementation for adapter contract tests, including idempotency and partial capacity.
 */
public final class InMemoryInventoryGateway implements PlatformPorts.InventoryGateway {
  private final int capacity;
  private final Map<UUID, State> inventories = new HashMap<>();
  private final Map<String, InventoryMutationResult> completed = new LinkedHashMap<>();

  public InMemoryInventoryGateway(int capacity) {
    if (capacity < 1) throw new IllegalArgumentException("capacity");
    this.capacity = capacity;
  }

  @Override
  public synchronized CompletionStage<PlatformResult<InventorySnapshot>> snapshot(
      UUID id, boolean offline) {
    State state = inventories.computeIfAbsent(id, ignored -> new State());
    return CompletableFuture.completedFuture(PlatformResult.success(snapshotOf(id, state)));
  }

  @Override
  public synchronized CompletionStage<PlatformResult<InventoryMutationResult>> compareAndApply(
      InventoryMutation mutation) {
    InventoryMutationResult prior = completed.get(mutation.operationId());
    if (prior != null) return CompletableFuture.completedFuture(PlatformResult.success(prior));
    State state = inventories.computeIfAbsent(mutation.playerId(), ignored -> new State());
    if (state.version != mutation.expectedVersion()) {
      return CompletableFuture.completedFuture(
          new PlatformResult.Conflict<>(mutation.operationId(), Long.toString(state.version)));
    }
    List<ItemEnvelope> next = new ArrayList<>(state.items);
    List<ItemEnvelope> removed = new ArrayList<>();
    for (InventoryRemoval removal : mutation.removals()) {
      ItemEnvelope requested = removal.expectedStack();
      int index = indexOf(next, requested);
      if (index < 0) {
        return CompletableFuture.completedFuture(
            PlatformResult.rejected("INVENTORY_ITEM_MISSING", "error.inventory.item_missing"));
      }
      ItemEnvelope source = next.remove(index);
      removed.add(withCount(source, removal.quantity()));
      if (source.count() > removal.quantity()) {
        next.add(index, withCount(source, source.count() - removal.quantity()));
      }
    }
    int room = Math.max(0, capacity - next.size());
    int insertedCount = Math.min(room, mutation.insertions().size());
    List<ItemEnvelope> inserted = List.copyOf(mutation.insertions().subList(0, insertedCount));
    List<ItemEnvelope> remainder =
        List.copyOf(mutation.insertions().subList(insertedCount, mutation.insertions().size()));
    next.addAll(inserted);
    state.items = next;
    state.version++;
    InventoryMutationResult result =
        new InventoryMutationResult(state.version, inserted, removed, remainder);
    completed.put(mutation.operationId(), result);
    return CompletableFuture.completedFuture(PlatformResult.success(result));
  }

  @Override
  public synchronized CompletionStage<PlatformResult<InventoryMutationResult>> operationResult(
      String operationId) {
    InventoryMutationResult result = completed.get(operationId);
    return CompletableFuture.completedFuture(
        result == null
            ? new PlatformResult.UnknownOutcome<>(operationId, true)
            : PlatformResult.success(result));
  }

  private InventorySnapshot snapshotOf(UUID id, State state) {
    List<ItemEnvelope> indexed = new ArrayList<>();
    for (int index = 0; index < state.items.size(); index++) {
      ItemEnvelope source = state.items.get(index);
      Map<String, String> summary = new HashMap<>(source.summary());
      summary.put("webshopx.slot", Integer.toString(index));
      indexed.add(
          new ItemEnvelope(
              source.schemaVersion(),
              source.codec(),
              source.codecVersion(),
              source.compatibilityDomain(),
              source.registryId(),
              source.count(),
              source.payloadEncoding(),
              source.payload(),
              source.payloadHash(),
              summary,
              source.createdAt()));
    }
    return new InventorySnapshot(id, state.version, capacity - state.items.size(), indexed);
  }

  private static int indexOf(List<ItemEnvelope> values, ItemEnvelope requested) {
    for (int index = 0; index < values.size(); index++) {
      ItemEnvelope value = values.get(index);
      if (value.payloadHash().equals(requested.payloadHash())
          && value.registryId().equals(requested.registryId())
          && value.count() == requested.count()) return index;
    }
    return -1;
  }

  private static ItemEnvelope withCount(ItemEnvelope source, int count) {
    return new ItemEnvelope(
        source.schemaVersion(),
        source.codec(),
        source.codecVersion(),
        source.compatibilityDomain(),
        source.registryId(),
        count,
        source.payloadEncoding(),
        source.payload(),
        source.payloadHash(),
        source.summary(),
        source.createdAt());
  }

  private static final class State {
    private long version;
    private List<ItemEnvelope> items = new ArrayList<>();
  }
}
