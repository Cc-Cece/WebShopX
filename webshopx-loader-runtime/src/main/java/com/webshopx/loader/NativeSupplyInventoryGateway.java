package com.webshopx.loader;

import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformIdentity;
import com.webshopx.platform.PlatformResult;
import com.webshopx.platform.SupplyInventoryGateway;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Main-thread native block-container adapter for Fabric, Forge and NeoForge. */
final class NativeSupplyInventoryGateway implements SupplyInventoryGateway {
  private static final int COMPLETED_LIMIT = 10_000;
  private final LoaderScheduler scheduler;
  private final NativeItemCodec items;
  private final PlatformIdentity identity;
  private final Map<String, SupplyWithdrawal> completed = new LinkedHashMap<>();

  NativeSupplyInventoryGateway(
      LoaderScheduler scheduler, NativeItemCodec items, PlatformIdentity identity) {
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.items = Objects.requireNonNull(items, "items");
    this.identity = Objects.requireNonNull(identity, "identity");
  }

  @Override
  public CompletionStage<PlatformResult<SupplySnapshot>> snapshot(SupplyLocation location) {
    CompletableFuture<PlatformResult<SupplySnapshot>> result = new CompletableFuture<>();
    scheduler.runGlobal(() -> {
      try {
        result.complete(PlatformResult.success(read(location)));
      } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
        result.complete(new PlatformResult.Unavailable<>(
            "supply_inventory", failure.getClass().getSimpleName(), Duration.ZERO));
      }
    }).whenComplete((ignored, failure) -> {
      if (failure != null) result.complete(new PlatformResult.Unavailable<>(
          "supply_inventory", "native scheduler is unavailable", Duration.ZERO));
    });
    return result;
  }

  @Override
  public CompletionStage<PlatformResult<SupplyWithdrawal>> compareAndWithdraw(
      SupplyWithdrawalRequest request) {
    synchronized (completed) {
      SupplyWithdrawal prior = completed.get(request.operationId());
      if (prior != null) return CompletableFuture.completedFuture(PlatformResult.success(prior));
    }
    CompletableFuture<PlatformResult<SupplyWithdrawal>> result = new CompletableFuture<>();
    scheduler.runGlobal(() -> result.complete(withdraw(request)))
        .whenComplete((ignored, failure) -> {
          if (failure != null) result.complete(new PlatformResult.Unavailable<>(
              "supply_inventory", "native scheduler is unavailable", Duration.ZERO));
        });
    return result;
  }

  private PlatformResult<SupplyWithdrawal> withdraw(SupplyWithdrawalRequest request) {
    boolean mutated = false;
    try {
      synchronized (completed) {
        SupplyWithdrawal prior = completed.get(request.operationId());
        if (prior != null) return PlatformResult.success(prior);
      }
      Object inventory = container(request.location());
      long before = version(inventory);
      if (before != request.expectedVersion()) {
        return new PlatformResult.Conflict<>(
            request.operationId(), Long.toUnsignedString(before));
      }
      String expectedIdentity = normalizedIdentity(request.expectedTemplate());
      int remaining = request.maximumQuantity();
      Object removedStack = null;
      int removed = 0;
      for (int slot = 0; slot < NativeInventoryGateway.size(inventory) && remaining > 0; slot++) {
        Object stack = NativeInventoryGateway.get(inventory, slot);
        if (NativeInventoryGateway.isEmpty(stack) || !sameItem(stack, expectedIdentity)) continue;
        int count = NativeItemCodec.count(stack);
        int take = Math.min(count, remaining);
        if (removedStack == null) removedStack = NativeInventoryGateway.copyStack(stack);
        NativeInventoryGateway.setCount(stack, count - take);
        NativeInventoryGateway.set(inventory, slot, stack);
        removed += take;
        remaining -= take;
        mutated = true;
      }
      NativeInventoryGateway.markChanged(inventory);
      ItemEnvelope envelope = null;
      if (removed > 0) {
        NativeInventoryGateway.setCount(removedStack, removed);
        PlatformResult<ItemEnvelope> encoded = items.encode(removedStack, identity);
        if (!(encoded instanceof PlatformResult.Success<ItemEnvelope> success)) {
          return new PlatformResult.UnknownOutcome<>(request.operationId(), true);
        }
        envelope = success.value();
      }
      SupplyWithdrawal withdrawal = new SupplyWithdrawal(version(inventory), envelope, removed);
      remember(request.operationId(), withdrawal);
      return PlatformResult.success(withdrawal);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
      return mutated
          ? new PlatformResult.UnknownOutcome<>(request.operationId(), true)
          : PlatformResult.rejected("SUPPLY_WITHDRAW_FAILED", "error.market.supply_failed");
    }
  }

  private SupplySnapshot read(SupplyLocation location) throws ReflectiveOperationException {
    Object inventory = container(location);
    List<ItemEnvelope> encoded = new ArrayList<>();
    for (int slot = 0; slot < NativeInventoryGateway.size(inventory); slot++) {
      Object stack = NativeInventoryGateway.get(inventory, slot);
      if (NativeInventoryGateway.isEmpty(stack)) continue;
      PlatformResult<ItemEnvelope> item = items.encode(stack, identity);
      if (!(item instanceof PlatformResult.Success<ItemEnvelope> success)) {
        throw new IllegalStateException("supply item cannot be encoded");
      }
      encoded.add(success.value());
    }
    return new SupplySnapshot(version(inventory), encoded);
  }

  private Object container(SupplyLocation location) throws ReflectiveOperationException {
    Object server = scheduler.nativeServer();
    if (server == null) throw new IllegalStateException("native server is not ready");
    Object level = null;
    Method levels = NativeItemCodec.method(
        server.getClass(), new String[] {"getAllLevels", "m_129785_"}, 0);
    Object candidates = levels.invoke(server);
    if (!(candidates instanceof Iterable<?> iterable)) {
      throw new IllegalStateException("server levels are not iterable");
    }
    for (Object candidate : iterable) {
      if (worldName(candidate).equals(location.world())) {
        level = candidate;
        break;
      }
    }
    if (level == null) throw new IllegalStateException("supply world is unavailable");
    Object position = blockPosition(level.getClass().getClassLoader(), location);
    Method blockEntity = NativeItemCodec.method(
        level.getClass(), new String[] {"getBlockEntity", "method_8321", "m_7702_"}, 1);
    Object container = blockEntity.invoke(level, position);
    if (container == null) throw new IllegalStateException("supply block entity is unavailable");
    NativeInventoryGateway.size(container);
    return container;
  }

  private String normalizedIdentity(ItemEnvelope template) throws ReflectiveOperationException {
    PlatformResult<Object> decoded = items.decode(template, items.domain());
    if (!(decoded instanceof PlatformResult.Success<Object> success)) {
      throw new IllegalStateException("supply template is incompatible with this node");
    }
    Object copy = NativeInventoryGateway.copyStack(success.value());
    NativeInventoryGateway.setCount(copy, 1);
    PlatformResult<ItemEnvelope> encoded = items.encode(copy, identity);
    if (!(encoded instanceof PlatformResult.Success<ItemEnvelope> normalized)) {
      throw new IllegalStateException("supply template cannot be normalized");
    }
    return normalized.value().payloadHash();
  }

  private boolean sameItem(Object stack, String expectedIdentity) throws ReflectiveOperationException {
    Object copy = NativeInventoryGateway.copyStack(stack);
    NativeInventoryGateway.setCount(copy, 1);
    PlatformResult<ItemEnvelope> encoded = items.encode(copy, identity);
    return encoded instanceof PlatformResult.Success<ItemEnvelope> success
        && success.value().payloadHash().equals(expectedIdentity);
  }

  private long version(Object inventory) throws ReflectiveOperationException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
    int size = NativeInventoryGateway.size(inventory);
    for (int slot = 0; slot < size; slot++) {
      Object stack = NativeInventoryGateway.get(inventory, slot);
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(slot).array());
      if (NativeInventoryGateway.isEmpty(stack)) {
        digest.update((byte) 0);
        continue;
      }
      PlatformResult<ItemEnvelope> encoded = items.encode(stack, identity);
      if (!(encoded instanceof PlatformResult.Success<ItemEnvelope> success)) {
        throw new IllegalStateException("supply item cannot be encoded");
      }
      digest.update(success.value().payloadHash().getBytes(StandardCharsets.US_ASCII));
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(success.value().count()).array());
    }
    return ByteBuffer.wrap(digest.digest()).getLong();
  }

  private static String worldName(Object level) throws ReflectiveOperationException {
    Object key = NativeItemCodec.method(
        level.getClass(), new String[] {"dimension", "method_27983", "m_46472_"}, 0)
        .invoke(level);
    Object location = NativeItemCodec.method(
        key.getClass(), new String[] {"location", "getValue", "method_29177", "m_135782_"}, 0)
        .invoke(key);
    return location.toString();
  }

  private static Object blockPosition(ClassLoader loader, SupplyLocation location)
      throws ReflectiveOperationException {
    for (String name : List.of("net.minecraft.core.BlockPos", "net.minecraft.class_2338")) {
      try {
        Class<?> type = Class.forName(name, false, loader);
        Constructor<?> constructor = Arrays.stream(type.getConstructors())
            .filter(candidate -> candidate.getParameterCount() == 3)
            .filter(candidate -> Arrays.equals(
                candidate.getParameterTypes(), new Class<?>[] {int.class, int.class, int.class}))
            .findFirst().orElseThrow();
        return constructor.newInstance(location.x(), location.y(), location.z());
      } catch (ClassNotFoundException ignored) {
        // Try the other runtime namespace.
      }
    }
    throw new ClassNotFoundException("BlockPos");
  }

  private void remember(String operationId, SupplyWithdrawal result) {
    synchronized (completed) {
      completed.put(operationId, result);
      while (completed.size() > COMPLETED_LIMIT) {
        completed.remove(completed.keySet().iterator().next());
      }
    }
  }
}
