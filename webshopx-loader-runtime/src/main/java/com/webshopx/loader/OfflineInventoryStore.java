package com.webshopx.loader;

import com.webshopx.platform.InventoryTypes.InventoryMutation;
import com.webshopx.platform.InventoryTypes.InventoryMutationResult;
import com.webshopx.platform.InventoryTypes.InventoryRemoval;
import com.webshopx.platform.InventoryTypes.InventorySnapshot;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformIdentity;
import com.webshopx.platform.PlatformResult;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/** Offline playerdata adapter. All calls are made on the native server thread. */
final class OfflineInventoryStore {
  private static final int MAIN_SLOTS = 36;
  private final NativeItemCodec items;
  private final PlatformIdentity identity;
  private final LoaderScheduler scheduler;

  OfflineInventoryStore(
      NativeItemCodec items, PlatformIdentity identity, LoaderScheduler scheduler) {
    this.items = items;
    this.identity = identity;
    this.scheduler = scheduler;
  }

  PlatformResult<InventorySnapshot> snapshot(UUID playerId) {
    Path file = playerFile(playerId);
    if (!Files.isRegularFile(file))
      return new PlatformResult.Unavailable<>(
          "offline_inventory", "offline player data does not exist", Duration.ZERO);
    try {
      OfflineData data = read(file, playerId);
      return PlatformResult.success(data.snapshot());
    } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError failure) {
      return PlatformResult.rejected(
          "OFFLINE_INVENTORY_READ_FAILED", "error.inventory.offline_read_failed");
    }
  }

  PlatformResult<InventoryMutationResult> compareAndApply(InventoryMutation mutation) {
    Path file = playerFile(mutation.playerId());
    if (!Files.isRegularFile(file))
      return new PlatformResult.Unavailable<>(
          "offline_inventory", "offline player data does not exist", Duration.ZERO);
    boolean written = false;
    try {
      OfflineData data = read(file, mutation.playerId());
      if (data.snapshot().version() != mutation.expectedVersion()) {
        return new PlatformResult.Conflict<>(
            mutation.operationId(), Long.toUnsignedString(data.snapshot().version()));
      }
      List<SlotItem> next = new ArrayList<>(data.slots());
      List<ItemEnvelope> removed = new ArrayList<>();
      for (InventoryRemoval removal : mutation.removals()) {
        ItemEnvelope requested = removal.expectedStack();
        int index = matching(next, requested);
        if (index < 0)
          return PlatformResult.rejected("INVENTORY_ITEM_MISSING", "error.inventory.item_missing");
        SlotItem source = next.remove(index);
        Object nativeStack = items.decodeNativeTag(source.tag());
        Object removedStack = copyStack(nativeStack);
        setCount(removedStack, removal.quantity());
        PlatformResult<ItemEnvelope> encodedRemoval = items.encode(removedStack, identity);
        if (!(encodedRemoval instanceof PlatformResult.Success<ItemEnvelope> removedItem)) {
          return PlatformResult.rejected("INVENTORY_ITEM_INVALID", "error.inventory.item_invalid");
        }
        removed.add(removedItem.value());
        if (removal.quantity() < requested.count()) {
          setCount(nativeStack, requested.count() - removal.quantity());
          PlatformResult<ItemEnvelope> encodedRemainder = items.encode(nativeStack, identity);
          if (!(encodedRemainder instanceof PlatformResult.Success<ItemEnvelope> remainder)) {
            return PlatformResult.rejected(
                "INVENTORY_ITEM_INVALID", "error.inventory.item_invalid");
          }
          Object remainderTag = items.envelopeTag(remainder.value());
          putByte(remainderTag, "Slot", (byte) source.slot());
          next.add(index, new SlotItem(source.slot(), remainder.value(), remainderTag));
        }
      }
      boolean[] occupied = new boolean[MAIN_SLOTS];
      for (SlotItem value : next)
        if (value.slot() >= 0 && value.slot() < MAIN_SLOTS) {
          occupied[value.slot()] = true;
        }
      List<Integer> free = new ArrayList<>();
      for (int slot = 0; slot < MAIN_SLOTS; slot++) if (!occupied[slot]) free.add(slot);
      int accepted = Math.min(free.size(), mutation.insertions().size());
      for (int index = 0; index < accepted; index++) {
        ItemEnvelope insertion = mutation.insertions().get(index);
        Object tag = items.envelopeTag(insertion);
        putByte(tag, "Slot", (byte) (int) free.get(index));
        next.add(new SlotItem(free.get(index), insertion, tag));
      }
      write(file, data.root(), next);
      written = true;
      InventorySnapshot after = snapshotOf(mutation.playerId(), next);
      return PlatformResult.success(
          new InventoryMutationResult(
              after.version(),
              mutation.insertions().subList(0, accepted),
              removed,
              mutation.insertions().subList(accepted, mutation.insertions().size())));
    } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError failure) {
      if (written) return new PlatformResult.UnknownOutcome<>(mutation.operationId(), true);
      return PlatformResult.rejected(
          "OFFLINE_INVENTORY_APPLY_FAILED", "error.inventory.offline_apply_failed");
    }
  }

  private OfflineData read(Path file, UUID playerId)
      throws ReflectiveOperationException, IOException {
    Object root = readCompressed(file);
    Object list = inventoryList(root);
    List<SlotItem> slots = new ArrayList<>();
    if (list instanceof List<?> values) {
      for (Object tag : values) {
        Object nativeItem = items.decodeNativeTag(tag);
        PlatformResult<ItemEnvelope> encoded = items.encode(nativeItem, identity);
        if (!(encoded instanceof PlatformResult.Success<ItemEnvelope> success)) {
          throw new IllegalStateException("offline item cannot be encoded");
        }
        slots.add(new SlotItem(readByte(tag, "Slot"), success.value(), tag));
      }
    }
    return new OfflineData(root, List.copyOf(slots), snapshotOf(playerId, slots));
  }

  private void write(Path destination, Object root, List<SlotItem> values)
      throws ReflectiveOperationException, IOException {
    ClassLoader loader = scheduler.nativeServer().getClass().getClassLoader();
    Class<?> listType = loadFirst(loader, "net.minecraft.nbt.ListTag", "net.minecraft.class_2499");
    Object list = listType.getConstructor().newInstance();
    @SuppressWarnings("unchecked")
    List<Object> nativeList = (List<Object>) list;
    values.stream()
        .sorted(Comparator.comparingInt(SlotItem::slot))
        .forEach(value -> nativeList.add(value.tag()));
    putTag(root, "Inventory", list);

    Files.createDirectories(destination.getParent());
    Path temporary = destination.resolveSibling(destination.getFileName() + ".webshopx.tmp");
    Path backup = destination.resolveSibling(destination.getFileName() + ".webshopx.bak");
    writeCompressed(root, temporary);
    Files.copy(destination, backup, StandardCopyOption.REPLACE_EXISTING);
    try {
      Files.move(
          temporary,
          destination,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private Object readCompressed(Path path) throws ReflectiveOperationException {
    ClassLoader loader = scheduler.nativeServer().getClass().getClassLoader();
    Class<?> io = loadFirst(loader, "net.minecraft.nbt.NbtIo", "net.minecraft.class_2507");
    for (Method method : io.getMethods()) {
      if (!Modifier.isStatic(method.getModifiers())) continue;
      if (!named(method, "readCompressed", "method_30613", "m_128937_")) continue;
      if (method.getParameterCount() == 1) {
        Object argument = method.getParameterTypes()[0] == Path.class ? path : path.toFile();
        return method.invoke(null, argument);
      }
      if (method.getParameterCount() == 2 && method.getParameterTypes()[0] == Path.class) {
        Class<?> accounter = method.getParameterTypes()[1];
        Object unlimited =
            NativeItemCodec.method(accounter, new String[] {"unlimitedHeap"}, 0).invoke(null);
        return method.invoke(null, path, unlimited);
      }
    }
    throw new NoSuchMethodException("NbtIo.readCompressed");
  }

  private void writeCompressed(Object root, Path path) throws ReflectiveOperationException {
    ClassLoader loader = scheduler.nativeServer().getClass().getClassLoader();
    Class<?> io = loadFirst(loader, "net.minecraft.nbt.NbtIo", "net.minecraft.class_2507");
    for (Method method : io.getMethods()) {
      if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 2) continue;
      if (!named(method, "writeCompressed", "method_30614", "m_128944_")) continue;
      if (!method.getParameterTypes()[0].isInstance(root)) continue;
      Object target = method.getParameterTypes()[1] == Path.class ? path : path.toFile();
      method.invoke(null, root, target);
      return;
    }
    throw new NoSuchMethodException("NbtIo.writeCompressed");
  }

  private static Object inventoryList(Object root) throws ReflectiveOperationException {
    try {
      Object value =
          NativeItemCodec.method(root.getClass(), new String[] {"getListOrEmpty"}, 1)
              .invoke(root, "Inventory");
      return unwrapOptional(value);
    } catch (NoSuchMethodException modernUnavailable) {
      return NativeItemCodec.method(
              root.getClass(), new String[] {"getList", "method_10554", "m_128437_"}, 2)
          .invoke(root, "Inventory", 10);
    }
  }

  private static int readByte(Object tag, String key) throws ReflectiveOperationException {
    try {
      Object value =
          NativeItemCodec.method(tag.getClass(), new String[] {"getByteOr"}, 2)
              .invoke(tag, key, (byte) 0);
      return ((Number) value).byteValue();
    } catch (NoSuchMethodException modernUnavailable) {
      Object value =
          NativeItemCodec.method(
                  tag.getClass(), new String[] {"getByte", "method_10571", "m_128445_"}, 1)
              .invoke(tag, key);
      value = unwrapOptional(value);
      return value instanceof Number number ? number.byteValue() : 0;
    }
  }

  private static void putByte(Object tag, String key, byte value)
      throws ReflectiveOperationException {
    NativeItemCodec.method(tag.getClass(), new String[] {"putByte", "method_10567", "m_128344_"}, 2)
        .invoke(tag, key, value);
  }

  private static void putTag(Object root, String key, Object tag)
      throws ReflectiveOperationException {
    NativeItemCodec.method(root.getClass(), new String[] {"put", "method_10566", "m_128365_"}, 2)
        .invoke(root, key, tag);
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

  private static Object unwrapOptional(Object value) {
    return value instanceof Optional<?> optional ? optional.orElse(null) : value;
  }

  private static int matching(List<SlotItem> values, ItemEnvelope requested) {
    for (int index = 0; index < values.size(); index++) {
      ItemEnvelope value = values.get(index).envelope();
      if (value.payloadHash().equals(requested.payloadHash())
          && value.registryId().equals(requested.registryId())
          && value.count() == requested.count()) return index;
    }
    return -1;
  }

  private static InventorySnapshot snapshotOf(UUID playerId, List<SlotItem> values) {
    boolean[] occupied = new boolean[MAIN_SLOTS];
    MessageDigest digest = sha256();
    List<ItemEnvelope> envelopes = new ArrayList<>();
    values.stream()
        .sorted(Comparator.comparingInt(SlotItem::slot))
        .forEach(
            value -> {
              if (value.slot() >= 0 && value.slot() < MAIN_SLOTS) occupied[value.slot()] = true;
              digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.slot()).array());
              digest.update(value.envelope().payloadHash().getBytes(StandardCharsets.US_ASCII));
              envelopes.add(value.envelope());
            });
    int free = 0;
    for (boolean used : occupied) if (!used) free++;
    return new InventorySnapshot(
        playerId, ByteBuffer.wrap(digest.digest()).getLong(), free, envelopes);
  }

  private static Path playerFile(UUID playerId) {
    Path root = Path.of(System.getProperty("webshopx.world-dir", System.getProperty("user.dir")));
    String level = "world";
    Path propertiesFile = root.resolve("server.properties");
    if (Files.isRegularFile(propertiesFile)) {
      Properties properties = new Properties();
      try (var input = Files.newInputStream(propertiesFile)) {
        properties.load(input);
        level = properties.getProperty("level-name", level).trim();
      } catch (IOException ignored) {
        // The default level name is still a safe read-only fallback.
      }
    }
    return root.resolve(level).resolve("playerdata").resolve(playerId + ".dat");
  }

  private static Class<?> loadFirst(ClassLoader loader, String... names)
      throws ClassNotFoundException {
    for (String name : names) {
      try {
        return Class.forName(name, false, loader);
      } catch (ClassNotFoundException ignored) {
        // Try the other runtime namespace.
      }
    }
    throw new ClassNotFoundException(String.join(",", names));
  }

  private static boolean named(Method method, String... names) {
    for (String name : names) if (method.getName().equals(name)) return true;
    return false;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record SlotItem(int slot, ItemEnvelope envelope, Object tag) {}

  private record OfflineData(Object root, List<SlotItem> slots, InventorySnapshot snapshot) {}
}
