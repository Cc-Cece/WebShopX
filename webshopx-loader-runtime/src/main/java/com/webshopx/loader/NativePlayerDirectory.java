package com.webshopx.loader;

import com.webshopx.platform.PlatformPorts;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/** Online-player index populated by native connection events without exposing Minecraft classes. */
final class NativePlayerDirectory implements PlatformPorts.PlayerDirectory {
  private final Map<UUID, PlatformPorts.PlayerSnapshot> online = new ConcurrentHashMap<>();
  private final Map<UUID, Object> nativePlayers = new ConcurrentHashMap<>();
  private final String serverId;

  NativePlayerDirectory(String serverId) {
    this.serverId = serverId;
  }

  @Override
  public CompletionStage<Optional<PlatformPorts.PlayerSnapshot>> find(UUID playerId) {
    return CompletableFuture.completedFuture(Optional.ofNullable(online.get(playerId)));
  }

  @Override
  public CompletionStage<Optional<PlatformPorts.PlayerSnapshot>> find(String playerName) {
    return CompletableFuture.completedFuture(online.values().stream()
        .filter(player -> player.name().equalsIgnoreCase(playerName)).findFirst());
  }

  @Override
  public CompletionStage<List<PlatformPorts.PlayerSnapshot>> onlinePlayers() {
    return CompletableFuture.completedFuture(List.copyOf(online.values()));
  }

  Optional<PlatformPorts.PlayerSnapshot> joined(Object eventOrHandler) {
    Object nativePlayer = findServerPlayer(eventOrHandler).orElse(null);
    Optional<Profile> nativeProfile = nativePlayer == null
        ? profile(eventOrHandler) : directProfile(nativePlayer);
    nativeProfile.ifPresent(profile -> online.put(profile.id(),
        new PlatformPorts.PlayerSnapshot(profile.id(), profile.name(), true, serverId, Locale.ROOT)));
    nativeProfile.ifPresent(profile -> {
      if (nativePlayer != null) nativePlayers.put(profile.id(), nativePlayer);
    });
    return nativeProfile.map(profile -> online.get(profile.id()));
  }

  Optional<PlatformPorts.PlayerSnapshot> disconnected(Object eventOrHandler) {
    Optional<Profile> nativeProfile = profile(eventOrHandler);
    return nativeProfile.map(profile -> {
      nativePlayers.remove(profile.id());
      PlatformPorts.PlayerSnapshot removed = online.remove(profile.id());
      return removed == null
          ? new PlatformPorts.PlayerSnapshot(profile.id(), profile.name(), false, serverId, Locale.ROOT)
          : new PlatformPorts.PlayerSnapshot(
              removed.id(), removed.name(), false, removed.serverId(), removed.locale());
    });
  }

  void clear() {
    online.clear();
    nativePlayers.clear();
  }

  boolean sendText(UUID playerId, String text) {
    Object player = nativePlayers.get(playerId);
    if (player == null) return false;
    try {
      ClassLoader loader = player.getClass().getClassLoader();
      Class<?> component = loadFirst(loader,
          "net.minecraft.network.chat.Component", "net.minecraft.class_2561");
      Method factory = Arrays.stream(component.getMethods())
          .filter(method -> Modifier.isStatic(method.getModifiers()))
          .filter(method -> method.getParameterCount() == 1 && method.getParameterTypes()[0] == String.class)
          .filter(method -> component.isAssignableFrom(method.getReturnType()))
          .sorted((left, right) -> factoryPriority(left.getName()) - factoryPriority(right.getName()))
          .findFirst().orElseThrow(() -> new NoSuchMethodException("text component factory"));
      Object message = factory.invoke(null, text);
      Method sender = Arrays.stream(player.getClass().getMethods())
          .filter(method -> method.getParameterCount() == 1)
          .filter(method -> method.getReturnType() == void.class)
          .filter(method -> method.getParameterTypes()[0].isInstance(message))
          .filter(method -> method.getName().equals("sendSystemMessage")
              || method.getName().equals("sendMessage") || method.getName().equals("method_43496"))
          .findFirst().orElseThrow(() -> new NoSuchMethodException("player system-message method"));
      sender.invoke(player, message);
      return true;
    } catch (ReflectiveOperationException | LinkageError failure) {
      throw new IllegalStateException("cannot send native player message", failure);
    }
  }

  Optional<Boolean> hasPermissionLevel(UUID playerId, int level) {
    Object player = nativePlayers.get(playerId);
    if (player == null) return Optional.empty();
    try {
      Method permission = Arrays.stream(player.getClass().getMethods())
          .filter(method -> method.getParameterCount() == 1 && method.getParameterTypes()[0] == int.class)
          .filter(method -> method.getReturnType() == boolean.class)
          .filter(method -> method.getName().equals("hasPermissions")
              || method.getName().equals("hasPermissionLevel") || method.getName().equals("method_5687"))
          .findFirst().orElseThrow(() -> new NoSuchMethodException("player permission-level method"));
      return Optional.of((Boolean) permission.invoke(player, level));
    } catch (ReflectiveOperationException | LinkageError failure) {
      throw new IllegalStateException("cannot query native player permission", failure);
    }
  }

  static Optional<PlatformPorts.PlayerSnapshot> snapshotOf(Object nativeObject, String serverId) {
    return profile(nativeObject).map(profile -> new PlatformPorts.PlayerSnapshot(
        profile.id(), profile.name(), true, serverId, Locale.ROOT));
  }

  static Optional<PlatformPorts.PlayerSnapshot> commandSourcePlayer(Object commandSource, String serverId) {
    if (commandSource == null) return Optional.empty();
    for (Field field : fields(commandSource.getClass())) {
      if (!isGameType(field.getType())) continue;
      try {
        if (!field.trySetAccessible()) continue;
        Object candidate = field.get(commandSource);
        if (!isServerPlayer(candidate)) continue;
        Optional<Profile> candidateProfile = directProfile(candidate);
        if (candidateProfile.isPresent()) {
          return candidateProfile.map(profile -> snapshot(profile, serverId));
        }
      } catch (IllegalAccessException | RuntimeException ignored) {
        // A different direct entity field may still be readable.
      }
    }
    return Optional.empty();
  }

  private static boolean isServerPlayer(Object candidate) {
    if (candidate == null) return false;
    String name = candidate.getClass().getName();
    return name.equals("net.minecraft.class_3222")
        || name.endsWith(".ServerPlayer") || name.endsWith("$ServerPlayer");
  }

  private static Optional<Object> findServerPlayer(Object root) {
    if (root == null) return Optional.empty();
    ArrayDeque<Node> queue = new ArrayDeque<>();
    IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
    queue.add(new Node(root, 0));
    while (!queue.isEmpty()) {
      Node node = queue.removeFirst();
      Object value = node.value();
      if (value == null || visited.put(value, Boolean.TRUE) != null) continue;
      if (isServerPlayer(value)) return Optional.of(value);
      if (node.depth() >= 3 || !isGameType(value.getClass())) continue;
      for (Field field : fields(value.getClass())) {
        if (!isGameType(field.getType())) continue;
        try {
          if (field.trySetAccessible()) {
            Object child = field.get(value);
            if (child != null) queue.addLast(new Node(child, node.depth() + 1));
          }
        } catch (IllegalAccessException | RuntimeException ignored) {
          // Continue scanning the remaining native fields.
        }
      }
    }
    return Optional.empty();
  }

  private static Class<?> loadFirst(ClassLoader loader, String... names) throws ClassNotFoundException {
    for (String name : names) {
      try {
        return Class.forName(name, false, loader);
      } catch (ClassNotFoundException ignored) {
        // Try the runtime namespace used by the next Loader.
      }
    }
    throw new ClassNotFoundException(String.join(", ", names));
  }

  private static int factoryPriority(String name) {
    if (name.equals("literal")) return 0;
    if (name.equals("of")) return 1;
    if (name.equals("method_43470")) return 2;
    return 10;
  }

  private static Optional<Profile> profile(Object root) {
    if (root == null) return Optional.empty();
    ArrayDeque<Node> queue = new ArrayDeque<>();
    IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
    queue.add(new Node(root, 0));
    while (!queue.isEmpty()) {
      Node node = queue.removeFirst();
      Object value = node.value();
      if (value == null || visited.put(value, Boolean.TRUE) != null) continue;
      if (value.getClass().getName().equals("com.mojang.authlib.GameProfile")) {
        try {
          Method getId = value.getClass().getMethod("getId");
          Method getName = value.getClass().getMethod("getName");
          Object id = getId.invoke(value);
          Object name = getName.invoke(value);
          if (id instanceof UUID uuid && name instanceof String text) return Optional.of(new Profile(uuid, text));
        } catch (ReflectiveOperationException ignored) {
          return Optional.empty();
        }
      }
      if (node.depth() >= 4 || !isGameType(value.getClass())) continue;
      for (Field field : fields(value.getClass())) {
        if (!isGameType(field.getType())) continue;
        try {
          if (!field.trySetAccessible()) continue;
          Object child = field.get(value);
          if (child != null) queue.addLast(new Node(child, node.depth() + 1));
        } catch (IllegalAccessException | RuntimeException ignored) {
          // Other fields may still expose the profile.
        }
      }
    }
    return Optional.empty();
  }

  private static Optional<Profile> directProfile(Object value) {
    if (value == null || !isGameType(value.getClass())) return Optional.empty();
    if (value.getClass().getName().equals("com.mojang.authlib.GameProfile")) {
      return readProfile(value);
    }
    for (Field field : fields(value.getClass())) {
      if (!field.getType().getName().equals("com.mojang.authlib.GameProfile")) continue;
      try {
        if (field.trySetAccessible()) return readProfile(field.get(value));
      } catch (IllegalAccessException | RuntimeException ignored) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  private static Optional<Profile> readProfile(Object value) {
    if (value == null) return Optional.empty();
    try {
      Method getId = value.getClass().getMethod("getId");
      Method getName = value.getClass().getMethod("getName");
      Object id = getId.invoke(value);
      Object name = getName.invoke(value);
      if (id instanceof UUID uuid && name instanceof String text) return Optional.of(new Profile(uuid, text));
    } catch (ReflectiveOperationException ignored) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private static PlatformPorts.PlayerSnapshot snapshot(Profile profile, String serverId) {
    return new PlatformPorts.PlayerSnapshot(
        profile.id(), profile.name(), true, serverId, Locale.ROOT);
  }

  private static boolean isGameType(Class<?> type) {
    String name = type.getName();
    return name.startsWith("net.minecraft.") || name.startsWith("com.mojang.authlib.")
        || name.startsWith("net.minecraftforge.") || name.startsWith("net.neoforged.");
  }

  private static List<Field> fields(Class<?> type) {
    List<Field> result = new ArrayList<>();
    for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) result.add(field);
    }
    return result;
  }

  private record Node(Object value, int depth) { }
  private record Profile(UUID id, String name) { }
}
