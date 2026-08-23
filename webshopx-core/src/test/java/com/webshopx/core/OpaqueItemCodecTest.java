package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformIdentity;
import com.webshopx.platform.PlatformResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpaqueItemCodecTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void preservesNativePayloadAndRejectsAnotherDomain() {
    OpaqueItemCodec codec = new OpaqueItemCodec("fabric-nbt", 1, CLOCK);
    PlatformIdentity identity = new PlatformIdentity("fabric", "fabric", "1.20.1", "0.19.3", "a", "pack-a");
    byte[] bytes = {10, 0, 4, 99, -1};
    var encoded = assertInstanceOf(PlatformResult.Success.class,
        codec.encode(new OpaqueItemCodec.NativeItem("example:widget", 2, bytes, Map.of("name", "Widget")), identity));
    ItemEnvelope envelope = (ItemEnvelope) encoded.value();
    CompatibilityDomain same = new CompatibilityDomain("fabric", "fabric", "1.20.1", 1, "pack-a");
    var decoded = assertInstanceOf(PlatformResult.Success.class, codec.decode(envelope, same));
    assertArrayEquals(bytes, ((OpaqueItemCodec.NativeItem) decoded.value()).payload());

    CompatibilityDomain other = new CompatibilityDomain("fabric", "fabric", "1.20.1", 1, "pack-b");
    assertInstanceOf(PlatformResult.Rejected.class, codec.decode(envelope, other));
    assertArrayEquals(bytes, envelope.payload());
  }
}
