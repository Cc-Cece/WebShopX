package com.mojang.authlib;

import java.util.UUID;

/** Minimal reflection fixture; production uses Mojang's GameProfile. */
public final class GameProfile {
  private final UUID id;
  private final String name;

  public GameProfile(UUID id, String name) {
    this.id = id;
    this.name = name;
  }

  public UUID getId() { return id; }
  public String getName() { return name; }
}
