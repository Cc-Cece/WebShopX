package com.webshopx.core;

import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformResult;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validates immutable native item payloads before any platform deserialization occurs. */
public final class ItemEnvelopeService {
  private final Clock clock;
  private final Set<String> acceptedCodecs;

  public ItemEnvelopeService(Clock clock, Set<String> acceptedCodecs) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.acceptedCodecs = Set.copyOf(acceptedCodecs);
  }

  public ItemEnvelope create(
      String codec,
      int codecVersion,
      CompatibilityDomain domain,
      String registryId,
      int count,
      byte[] nativePayload,
      Map<String, String> summary) {
    return new ItemEnvelope(
        1,
        codec,
        codecVersion,
        domain,
        registryId,
        count,
        ItemEnvelope.PayloadEncoding.RAW,
        nativePayload,
        sha256(nativePayload),
        summary,
        clock.instant());
  }

  public PlatformResult<ItemEnvelope> validate(
      ItemEnvelope envelope, CompatibilityDomain targetDomain) {
    if (!acceptedCodecs.contains(envelope.codec())) {
      return PlatformResult.rejected("ITEM_CODEC_UNKNOWN", "error.item.codec_unknown");
    }
    if (!MessageDigest.isEqual(
        envelope.payloadHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        sha256(envelope.payload()).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
      return PlatformResult.rejected("ITEM_HASH_MISMATCH", "error.item.hash_mismatch");
    }
    if (!envelope.compatibilityDomain().sameNativeDomain(targetDomain)) {
      return PlatformResult.rejected("ITEM_DOMAIN_INCOMPATIBLE", "error.item.domain_incompatible");
    }
    return PlatformResult.success(envelope);
  }

  public static String sha256(byte[] payload) {
    try {
      return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
