package com.webshopx.platform;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ItemEnvelope(
    int schemaVersion,
    String codec,
    int codecVersion,
    CompatibilityDomain compatibilityDomain,
    String registryId,
    int count,
    PayloadEncoding payloadEncoding,
    byte[] payload,
    String payloadHash,
    Map<String, String> summary,
    Instant createdAt) {

  public static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;
  public static final int MAX_COUNT = 99_999;

  public ItemEnvelope {
    if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
    Objects.requireNonNull(codec, "codec");
    if (codec.isBlank()) throw new IllegalArgumentException("codec must not be blank");
    if (codecVersion < 1) throw new IllegalArgumentException("codecVersion must be positive");
    Objects.requireNonNull(compatibilityDomain, "compatibilityDomain");
    Objects.requireNonNull(registryId, "registryId");
    if (!registryId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
      throw new IllegalArgumentException("invalid registryId");
    }
    if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException("invalid count");
    Objects.requireNonNull(payloadEncoding, "payloadEncoding");
    payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
    if (payload.length > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("payload too large");
    Objects.requireNonNull(payloadHash, "payloadHash");
    summary = Collections.unmodifiableMap(new LinkedHashMap<>(summary));
    Objects.requireNonNull(createdAt, "createdAt");
  }

  @Override
  public byte[] payload() {
    return Arrays.copyOf(payload, payload.length);
  }

  @Override
  public boolean equals(Object candidate) {
    if (this == candidate) return true;
    if (!(candidate instanceof ItemEnvelope other)) return false;
    return schemaVersion == other.schemaVersion
        && codecVersion == other.codecVersion
        && count == other.count
        && codec.equals(other.codec)
        && compatibilityDomain.equals(other.compatibilityDomain)
        && registryId.equals(other.registryId)
        && payloadEncoding == other.payloadEncoding
        && Arrays.equals(payload, other.payload)
        && payloadHash.equals(other.payloadHash)
        && summary.equals(other.summary)
        && createdAt.equals(other.createdAt);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(
        schemaVersion,
        codec,
        codecVersion,
        compatibilityDomain,
        registryId,
        count,
        payloadEncoding,
        payloadHash,
        summary,
        createdAt);
    return 31 * result + Arrays.hashCode(payload);
  }

  public enum PayloadEncoding { RAW, GZIP }
}
