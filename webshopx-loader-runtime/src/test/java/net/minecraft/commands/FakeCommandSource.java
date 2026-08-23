package net.minecraft.commands;

import com.mojang.authlib.GameProfile;

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

  private static final class ServerPlayer {
    private final GameProfile profile;
    private ServerPlayer(GameProfile profile) { this.profile = profile; }
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
