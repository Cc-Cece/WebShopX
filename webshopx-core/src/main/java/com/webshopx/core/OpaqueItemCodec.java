package com.webshopx.core;

import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformIdentity;
import com.webshopx.platform.PlatformPorts;
import com.webshopx.platform.PlatformResult;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Lossless codec for loader-native payloads. Native bytes are never interpreted by core. */
public final class OpaqueItemCodec implements PlatformPorts.ItemCodec<OpaqueItemCodec.NativeItem> {
  private final String id;
  private final int version;
  private final ItemEnvelopeService envelopes;

  public OpaqueItemCodec(String id, int version, Clock clock) {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("id");
    if (version < 1) throw new IllegalArgumentException("version");
    this.id = id;
    this.version = version;
    this.envelopes = new ItemEnvelopeService(clock, Set.of(id));
  }

  @Override public String id() { return id; }
  @Override public int version() { return version; }

  @Override
  public PlatformResult<ItemEnvelope> encode(NativeItem item, PlatformIdentity identity) {
    Objects.requireNonNull(item, "item");
    Objects.requireNonNull(identity, "identity");
    CompatibilityDomain domain = new CompatibilityDomain(
        identity.platform(), identity.loader(), identity.minecraftVersion(), version,
        identity.modpackFingerprint());
    return PlatformResult.success(envelopes.create(
        id, version, domain, item.registryId(), item.count(), item.payload(), item.summary()));
  }

  @Override
  public PlatformResult<NativeItem> decode(ItemEnvelope envelope, CompatibilityDomain targetDomain) {
    PlatformResult<ItemEnvelope> validated = envelopes.validate(envelope, targetDomain);
    if (validated instanceof PlatformResult.Success<ItemEnvelope> success) {
      ItemEnvelope value = success.value();
      return PlatformResult.success(new NativeItem(
          value.registryId(), value.count(), value.payload(), value.summary()));
    }
    if (validated instanceof PlatformResult.Rejected<ItemEnvelope> rejected) {
      return new PlatformResult.Rejected<>(
          rejected.errorCode(), rejected.messageKey(), rejected.retryable());
    }
    throw new IllegalStateException("unexpected envelope validation result");
  }

  public record NativeItem(String registryId, int count, byte[] payload, Map<String, String> summary) {
    public NativeItem {
      Objects.requireNonNull(registryId, "registryId");
      if (count < 1 || count > ItemEnvelope.MAX_COUNT) throw new IllegalArgumentException("count");
      payload = Objects.requireNonNull(payload, "payload").clone();
      summary = Map.copyOf(Objects.requireNonNull(summary, "summary"));
    }

    @Override public byte[] payload() { return payload.clone(); }
  }
}
