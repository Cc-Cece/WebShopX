package com.webshopx.loader;

import com.webshopx.platform.PlatformPorts;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
    Optional<Profile> nativeProfile = profile(eventOrHandler);
    nativeProfile.ifPresent(profile -> online.put(profile.id(),
        new PlatformPorts.PlayerSnapshot(profile.id(), profile.name(), true, serverId, Locale.ROOT)));
    return nativeProfile.map(profile -> online.get(profile.id()));
  }

  Optional<PlatformPorts.PlayerSnapshot> disconnected(Object eventOrHandler) {
    Optional<Profile> nativeProfile = profile(eventOrHandler);
    return nativeProfile.map(profile -> {
      PlatformPorts.PlayerSnapshot removed = online.remove(profile.id());
      return removed == null
          ? new PlatformPorts.PlayerSnapshot(profile.id(), profile.name(), false, serverId, Locale.ROOT)
          : new PlatformPorts.PlayerSnapshot(
              removed.id(), removed.name(), false, removed.serverId(), removed.locale());
    });
  }

  void clear() {
    online.clear();
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
