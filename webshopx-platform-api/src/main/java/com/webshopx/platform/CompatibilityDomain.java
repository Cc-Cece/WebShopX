package com.webshopx.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;

public record CompatibilityDomain(
    String platform,
    String loader,
    String minecraftVersion,
    int itemCodecVersion,
    String modpackFingerprint) {

  public static String fingerprint(Collection<ModIdentity> mods) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      mods.stream()
          .sorted(Comparator.comparing(ModIdentity::id).thenComparing(ModIdentity::version))
          .forEach(mod -> digest.update((mod.id() + "\u0000" + mod.version() + "\n")
              .getBytes(StandardCharsets.UTF_8)));
      return "sha256:" + HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public boolean sameNativeDomain(CompatibilityDomain other) {
    return other != null
        && platform.equals(other.platform)
        && loader.equals(other.loader)
        && minecraftVersion.equals(other.minecraftVersion)
        && itemCodecVersion == other.itemCodecVersion
        && modpackFingerprint.equals(other.modpackFingerprint);
  }

  public record ModIdentity(String id, String version) { }
}
