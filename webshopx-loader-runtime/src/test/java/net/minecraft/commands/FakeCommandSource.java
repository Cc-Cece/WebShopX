package net.minecraft.commands;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;

public final class FakeCommandSource {
  private final ServerPlayer entity;
  private final FakeServer server;

  private FakeCommandSource(ServerPlayer entity, GameProfile cachedProfile) {
    this.entity = entity;
    this.server = new FakeServer(cachedProfile);
  }

  public static FakeCommandSource player(GameProfile profile) {
    return new FakeCommandSource(new ServerPlayer(profile), profile);
  }

  public static FakeCommandSource console(GameProfile cachedProfile) {
    return new FakeCommandSource(null, cachedProfile);
  }

  public List<String> messages() {
    return entity == null ? List.of() : List.copyOf(entity.messages);
  }

  public static final class ServerPlayer {
    private final GameProfile profile;
    private final List<String> messages = new ArrayList<>();
    private ServerPlayer(GameProfile profile) { this.profile = profile; }
    public void sendSystemMessage(Component message) { messages.add(message.text()); }
  }

  private static final class FakeServer {
    private final FakeProfileCache cache;
    private FakeServer(GameProfile profile) { this.cache = new FakeProfileCache(profile); }
  }

  private static final class FakeProfileCache {
    private final GameProfile profile;
    private FakeProfileCache(GameProfile profile) { this.profile = profile; }
  }
}
