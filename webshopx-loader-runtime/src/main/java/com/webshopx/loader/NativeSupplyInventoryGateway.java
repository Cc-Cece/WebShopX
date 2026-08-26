package com.webshopx.loader;

import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformIdentity;
import com.webshopx.platform.PlatformResult;
import com.webshopx.platform.SupplyInventoryGateway;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Main-thread native block-container adapter for Fabric, Forge and NeoForge. */
final class NativeSupplyInventoryGateway implements SupplyInventoryGateway {
  private static final int COMPLETED_LIMIT = 10_000;
  private final LoaderScheduler scheduler;
  private final NativePlayerDirectory players;
  private final NativeItemCodec items;
  private final PlatformIdentity identity;
  private final SupplyOperationStore operationStore;
  private final Map<String, SupplyWithdrawal> completed = new LinkedHashMap<>();

  NativeSupplyInventoryGateway(
      LoaderScheduler scheduler,
      NativePlayerDirectory players,
      NativeItemCodec items,
      PlatformIdentity identity,
      Path dataDirectory) {
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.players = Objects.requireNonNull(players, "players");
    this.items = Objects.requireNonNull(items, "items");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.operationStore = new SupplyOperationStore(dataDirectory);
  }

  @Override
  public CompletionStage<PlatformResult<SupplySnapshot>> inspect(
      UUID playerId, SupplyLocation location) {
    CompletableFuture<PlatformResult<SupplySnapshot>> result = new CompletableFuture<>();
    scheduler.runGlobal(() -> {
      try {
        authorize(playerId, location);
        result.complete(PlatformResult.success(read(location)));
      } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
        result.complete(PlatformResult.rejected(
            "SUPPLY_INSPECTION_DENIED", "error.market.supply_inspection_denied"));
      }
    }).whenComplete((ignored, failure) -> {
      if (failure != null) result.complete(new PlatformResult.Unavailable<>(
          "supply_inventory", "native scheduler is unavailable", Duration.ZERO));
    });
    return result;
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
    var persisted = operationStore.read(request.operationId());
    if (persisted.isPresent()) return CompletableFuture.completedFuture(PlatformResult.success(persisted.get()));
    if (operationStore.pending(request.operationId())) return CompletableFuture.completedFuture(
        new PlatformResult.UnknownOutcome<>(request.operationId(), true));
    CompletableFuture<PlatformResult<SupplyWithdrawal>> result = new CompletableFuture<>();
    scheduler.runGlobal(() -> result.complete(withdraw(request)))
        .whenComplete((ignored, failure) -> {
          if (failure != null) result.complete(new PlatformResult.Unavailable<>(
              "supply_inventory", "native scheduler is unavailable", Duration.ZERO));
        });
    return result;
  }

  @Override
  public CompletionStage<PlatformResult<SupplyWithdrawal>> reconcile(
      SupplyWithdrawalRequest request) {
    synchronized (completed) {
      SupplyWithdrawal prior = completed.get(request.operationId());
      if (prior != null) return CompletableFuture.completedFuture(PlatformResult.success(prior));
    }
    var persisted = operationStore.read(request.operationId());
    return CompletableFuture.completedFuture(persisted.<PlatformResult<SupplyWithdrawal>>map(
        PlatformResult::success).orElseGet(() ->
        new PlatformResult.UnknownOutcome<>(request.operationId(), operationStore.pending(request.operationId()))));
  }

  private PlatformResult<SupplyWithdrawal> withdraw(SupplyWithdrawalRequest request) {
    boolean mutated = false;
    try {
      synchronized (completed) {
        SupplyWithdrawal prior = completed.get(request.operationId());
        if (prior != null) return PlatformResult.success(prior);
      }
      if (!operationStore.begin(request.operationId())) {
        return new PlatformResult.UnknownOutcome<>(request.operationId(), true);
      }
      Object inventory = container(request.location());
      long before = version(inventory);
      if (before != request.expectedVersion()) {
        operationStore.clearPending(request.operationId());
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
      operationStore.write(request.operationId(), withdrawal);
      remember(request.operationId(), withdrawal);
      return PlatformResult.success(withdrawal);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
      if (!mutated) operationStore.clearPending(request.operationId());
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

  private void authorize(UUID playerId, SupplyLocation location)
      throws ReflectiveOperationException {
    Object player = players.nativePlayer(playerId)
        .orElseThrow(() -> new IllegalStateException("player must be online"));
    Method levelMethod = NativeItemCodec.method(
        player.getClass(), new String[] {"serverLevel", "getLevel", "level", "method_37908"}, 0);
    Object level = levelMethod.invoke(player);
    if (!worldName(level).equals(location.world())) {
      throw new IllegalStateException("player is in another world");
    }
    double x = coordinate(player, new String[] {"getX", "method_23317", "m_20185_"});
    double y = coordinate(player, new String[] {"getY", "method_23318", "m_20186_"});
    double z = coordinate(player, new String[] {"getZ", "method_23321", "m_20189_"});
    double dx = x - (location.x() + 0.5D);
    double dy = y - (location.y() + 0.5D);
    double dz = z - (location.z() + 0.5D);
    if (dx * dx + dy * dy + dz * dz > 64D) {
      throw new IllegalStateException("player is too far from the supply container");
    }
    Object position = blockPosition(level.getClass().getClassLoader(), location);
    Method interaction = Arrays.stream(player.getClass().getMethods())
        .filter(method -> method.getName().equals("mayInteract")
            || method.getName().equals("method_5680") || method.getName().equals("m_36204_"))
        .filter(method -> method.getParameterCount() == 2)
        .filter(method -> method.getParameterTypes()[0].isInstance(level))
        .filter(method -> method.getParameterTypes()[1].isInstance(position))
        .findFirst().orElse(null);
    if (interaction != null && !((Boolean) interaction.invoke(player, level, position))) {
      throw new IllegalStateException("native interaction policy denied supply access");
    }
  }

  private static double coordinate(Object player, String[] names)
      throws ReflectiveOperationException {
    return ((Number) NativeItemCodec.method(player.getClass(), names, 0).invoke(player))
        .doubleValue();
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
