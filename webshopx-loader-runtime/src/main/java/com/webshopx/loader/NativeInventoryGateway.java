package com.webshopx.loader;

import com.webshopx.platform.InventoryTypes.InventoryMutation;
import com.webshopx.platform.InventoryTypes.InventoryMutationResult;
import com.webshopx.platform.InventoryTypes.InventoryRemoval;
import com.webshopx.platform.InventoryTypes.InventorySnapshot;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformIdentity;
import com.webshopx.platform.PlatformPorts;
import com.webshopx.platform.PlatformResult;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Main-thread online inventory adapter with optimistic versioning and operation idempotency. */
final class NativeInventoryGateway implements PlatformPorts.InventoryGateway {
  private static final int COMPLETED_LIMIT = 10_000;
  private final NativePlayerDirectory players;
  private final LoaderScheduler scheduler;
  private final NativeItemCodec items;
  private final PlatformIdentity identity;
  private final OfflineInventoryStore offline;
  private final Map<String, InventoryMutationResult> completed = new LinkedHashMap<>();

  NativeInventoryGateway(
      NativePlayerDirectory players,
      LoaderScheduler scheduler,
      NativeItemCodec items,
      PlatformIdentity identity) {
    this.players = players;
    this.scheduler = scheduler;
    this.items = items;
    this.identity = identity;
    this.offline = new OfflineInventoryStore(items, identity, scheduler);
  }

  @Override
  public CompletionStage<PlatformResult<InventorySnapshot>> snapshot(
      UUID playerId, boolean allowOffline) {
    Optional<Object> player = players.nativePlayer(playerId);
    if (player.isEmpty()) {
      if (!allowOffline)
        return CompletableFuture.completedFuture(
            new PlatformResult.Unavailable<>("inventory", "player is not online", Duration.ZERO));
      CompletableFuture<PlatformResult<InventorySnapshot>> result = new CompletableFuture<>();
      scheduler
          .runGlobal(
              () ->
                  result.complete(
                      players.nativePlayer(playerId).isPresent()
                          ? PlatformResult.rejected(
                              "PLAYER_STATE_CHANGED", "error.inventory.player_state_changed")
                          : offline.snapshot(playerId)))
          .whenComplete(
              (ignored, failure) -> {
                if (failure != null)
                  result.complete(
                      new PlatformResult.Unavailable<>(
                          "offline_inventory", "native scheduler is unavailable", Duration.ZERO));
              });
      return result;
    }
    CompletableFuture<PlatformResult<InventorySnapshot>> result = new CompletableFuture<>();
    scheduler
        .runForPlayer(
            playerId,
            () -> {
              try {
                result.complete(
                    PlatformResult.success(readSnapshot(playerId, player.orElseThrow())));
              } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                result.complete(
                    PlatformResult.rejected(
                        "INVENTORY_SNAPSHOT_FAILED", "error.inventory.snapshot_failed"));
              }
            })
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null)
                result.complete(
                    new PlatformResult.Unavailable<>(
                        "inventory", "native scheduler is unavailable", Duration.ZERO));
            });
    return result;
  }

  @Override
  public CompletionStage<PlatformResult<InventoryMutationResult>> compareAndApply(
      InventoryMutation mutation) {
    synchronized (completed) {
      InventoryMutationResult prior = completed.get(mutation.operationId());
      if (prior != null) return CompletableFuture.completedFuture(PlatformResult.success(prior));
    }
    Optional<Object> player = players.nativePlayer(mutation.playerId());
    if (player.isEmpty()) {
      CompletableFuture<PlatformResult<InventoryMutationResult>> result = new CompletableFuture<>();
      scheduler
          .runGlobal(
              () ->
                  result.complete(
                      players.nativePlayer(mutation.playerId()).isPresent()
                          ? PlatformResult.rejected(
                              "PLAYER_STATE_CHANGED", "error.inventory.player_state_changed")
                          : offline.compareAndApply(mutation)))
          .whenComplete(
              (ignored, failure) -> {
                if (failure != null)
                  result.complete(
                      new PlatformResult.Unavailable<>(
                          "offline_inventory", "native scheduler is unavailable", Duration.ZERO));
              });
      return result;
    }
    CompletableFuture<PlatformResult<InventoryMutationResult>> result = new CompletableFuture<>();
    scheduler
        .runForPlayer(
            mutation.playerId(),
            () -> result.complete(applyOnServerThread(mutation, player.orElseThrow())))
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null)
                result.complete(
                    new PlatformResult.Unavailable<>(
                        "inventory", "native scheduler is unavailable", Duration.ZERO));
            });
    return result;
  }

  private PlatformResult<InventoryMutationResult> applyOnServerThread(
      InventoryMutation mutation, Object player) {
    boolean mutated = false;
    try {
      synchronized (completed) {
        InventoryMutationResult prior = completed.get(mutation.operationId());
        if (prior != null) return PlatformResult.success(prior);
      }
      InventorySnapshot before = readSnapshot(mutation.playerId(), player);
      if (before.version() != mutation.expectedVersion()) {
        return new PlatformResult.Conflict<>(
            mutation.operationId(), Long.toUnsignedString(before.version()));
      }
      Object inventory = inventory(player);
      int size = size(inventory);
      boolean[] reserved = new boolean[size];
      List<RemovalPlan> removals = new ArrayList<>();
      for (InventoryRemoval removal : mutation.removals()) {
        ItemEnvelope requested = removal.expectedStack();
        int slot = matchingSlot(inventory, size, reserved, requested);
        if (slot < 0)
          return PlatformResult.rejected("INVENTORY_ITEM_MISSING", "error.inventory.item_missing");
        reserved[slot] = true;
        Object source = get(inventory, slot);
        Object removedStack = copyStack(source);
        setCount(removedStack, removal.quantity());
        PlatformResult<ItemEnvelope> encodedRemoval = items.encode(removedStack, identity);
        if (!(encodedRemoval instanceof PlatformResult.Success<ItemEnvelope> success)) {
          return PlatformResult.rejected("INVENTORY_ITEM_INVALID", "error.inventory.item_invalid");
        }
        removals.add(
            new RemovalPlan(slot, source, requested.count(), removal.quantity(), success.value()));
      }

      List<Object> decoded = new ArrayList<>();
      for (ItemEnvelope insertion : mutation.insertions()) {
        PlatformResult<Object> decodedItem = items.decode(insertion, items.domain());
        if (!(decodedItem instanceof PlatformResult.Success<Object> success)) {
          return PlatformResult.rejected("INVENTORY_ITEM_INVALID", "error.inventory.item_invalid");
        }
        decoded.add(success.value());
      }

      Object empty = emptyStack(inventory, size, player.getClass().getClassLoader());
      for (RemovalPlan removal : removals) {
        if (removal.quantity() == removal.originalCount()) {
          set(inventory, removal.slot(), empty);
        } else {
          setCount(removal.nativeStack(), removal.originalCount() - removal.quantity());
          set(inventory, removal.slot(), removal.nativeStack());
        }
        mutated = true;
      }
      List<Integer> free = freeSlots(inventory, size);
      int accepted = Math.min(free.size(), decoded.size());
      for (int index = 0; index < accepted; index++) {
        set(inventory, free.get(index), decoded.get(index));
        mutated = true;
      }
      markChanged(inventory);
      InventorySnapshot after = readSnapshot(mutation.playerId(), player);
      InventoryMutationResult applied =
          new InventoryMutationResult(
              after.version(),
              mutation.insertions().subList(0, accepted),
              removals.stream().map(RemovalPlan::removed).toList(),
              mutation.insertions().subList(accepted, mutation.insertions().size()));
      remember(mutation.operationId(), applied);
      return PlatformResult.success(applied);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
      if (mutated) return new PlatformResult.UnknownOutcome<>(mutation.operationId(), true);
      return PlatformResult.rejected("INVENTORY_APPLY_FAILED", "error.inventory.apply_failed");
    }
  }

  private InventorySnapshot readSnapshot(UUID playerId, Object player)
      throws ReflectiveOperationException {
    Object inventory = inventory(player);
    int size = size(inventory);
    List<ItemEnvelope> values = new ArrayList<>();
    int free = 0;
    MessageDigest digest = sha256();
    for (int slot = 0; slot < size; slot++) {
      Object stack = get(inventory, slot);
      if (isEmpty(stack)) {
        free++;
        digest.update((byte) 0);
      } else {
        PlatformResult<ItemEnvelope> encoded = items.encode(stack, identity);
        if (!(encoded instanceof PlatformResult.Success<ItemEnvelope> success)) {
          throw new IllegalStateException("native item cannot be encoded");
        }
        values.add(success.value());
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(slot).array());
        digest.update(success.value().payloadHash().getBytes(StandardCharsets.US_ASCII));
      }
    }
    return new InventorySnapshot(
        playerId, ByteBuffer.wrap(digest.digest()).getLong(), free, values);
  }

  private int matchingSlot(Object inventory, int size, boolean[] reserved, ItemEnvelope requested)
      throws ReflectiveOperationException {
    for (int slot = 0; slot < size; slot++) {
      if (reserved[slot]) continue;
      Object stack = get(inventory, slot);
      if (isEmpty(stack)) continue;
      PlatformResult<ItemEnvelope> encoded = items.encode(stack, identity);
      if (encoded instanceof PlatformResult.Success<ItemEnvelope> success
          && success.value().payloadHash().equals(requested.payloadHash())
          && success.value().registryId().equals(requested.registryId())
          && success.value().count() == requested.count()) return slot;
    }
    return -1;
  }

  private static Object inventory(Object player) throws ReflectiveOperationException {
    return NativeItemCodec.method(
            player.getClass(), new String[] {"getInventory", "method_31548", "m_150109_"}, 0)
        .invoke(player);
  }

  private static int size(Object inventory) throws ReflectiveOperationException {
    return (Integer)
        NativeItemCodec.method(
                inventory.getClass(),
                new String[] {"getContainerSize", "method_5439", "m_6643_"},
                0)
            .invoke(inventory);
  }

  private static Object get(Object inventory, int slot) throws ReflectiveOperationException {
    return NativeItemCodec.method(
            inventory.getClass(), new String[] {"getItem", "method_5438", "m_8020_"}, 1)
        .invoke(inventory, slot);
  }

  private static Object copyStack(Object stack) throws ReflectiveOperationException {
    return NativeItemCodec.method(
            stack.getClass(), new String[] {"copy", "method_7972", "m_41777_"}, 0)
        .invoke(stack);
  }

  private static void setCount(Object stack, int count) throws ReflectiveOperationException {
    NativeItemCodec.method(
            stack.getClass(), new String[] {"setCount", "method_7939", "m_41764_"}, 1)
        .invoke(stack, count);
  }

  private static void set(Object inventory, int slot, Object stack)
      throws ReflectiveOperationException {
    NativeItemCodec.method(
            inventory.getClass(), new String[] {"setItem", "method_5447", "m_6836_"}, 2)
        .invoke(inventory, slot, stack);
  }

  private static boolean isEmpty(Object stack) throws ReflectiveOperationException {
    return (Boolean)
        NativeItemCodec.method(
                stack.getClass(), new String[] {"isEmpty", "method_7960", "m_41619_"}, 0)
            .invoke(stack);
  }

  private static List<Integer> freeSlots(Object inventory, int size)
      throws ReflectiveOperationException {
    List<Integer> free = new ArrayList<>();
    for (int slot = 0; slot < size; slot++) if (isEmpty(get(inventory, slot))) free.add(slot);
    return free;
  }

  private static Object emptyStack(Object inventory, int size, ClassLoader loader)
      throws ReflectiveOperationException {
    if (size > 0) {
      Object candidate = get(inventory, 0);
      if (isEmpty(candidate)) return candidate;
      Class<?> type = candidate.getClass();
      for (String name : List.of("EMPTY", "field_8037", "f_41583_")) {
        try {
          Field field = type.getField(name);
          return field.get(null);
        } catch (NoSuchFieldException ignored) {
          // Try the next mapping.
        }
      }
    }
    for (String name : List.of("net.minecraft.world.item.ItemStack", "net.minecraft.class_1799")) {
      try {
        Class<?> type = Class.forName(name, false, loader);
        for (String fieldName : List.of("EMPTY", "field_8037", "f_41583_")) {
          try {
            return type.getField(fieldName).get(null);
          } catch (NoSuchFieldException ignored) {
            // Continue.
          }
        }
      } catch (ClassNotFoundException ignored) {
        // Try the other runtime namespace.
      }
    }
    throw new NoSuchFieldException("ItemStack.EMPTY");
  }

  private static void markChanged(Object inventory) {
    try {
      NativeItemCodec.method(
              inventory.getClass(), new String[] {"setChanged", "method_6596", "m_6596_"}, 0)
          .invoke(inventory);
    } catch (ReflectiveOperationException ignored) {
      // setItem already notifies inventories on versions without this public hook.
    }
  }

  private void remember(String operationId, InventoryMutationResult result) {
    synchronized (completed) {
      completed.put(operationId, result);
      while (completed.size() > COMPLETED_LIMIT) {
        completed.remove(completed.keySet().iterator().next());
      }
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record RemovalPlan(
      int slot, Object nativeStack, int originalCount, int quantity, ItemEnvelope removed) {}
}
