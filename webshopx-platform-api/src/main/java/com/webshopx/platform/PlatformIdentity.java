package com.webshopx.platform;

import java.util.Objects;

public record PlatformIdentity(
    String platform,
    String loader,
    String minecraftVersion,
    String loaderVersion,
    String serverId,
    String modpackFingerprint) {

  public PlatformIdentity {
    platform = required(platform, "platform");
    loader = required(loader, "loader");
    minecraftVersion = required(minecraftVersion, "minecraftVersion");
    loaderVersion = required(loaderVersion, "loaderVersion");
    serverId = required(serverId, "serverId");
    modpackFingerprint = required(modpackFingerprint, "modpackFingerprint");
  }

  private static String required(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    return value;
  }
}
