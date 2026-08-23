package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.ItemEnvelope;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ItemEnvelopeBinaryCodecTest {
  @Test void preservesTheCompleteEnvelopeAndRejectsTrailingData() {
    ItemEnvelope input = new ItemEnvelope(1, "native", 2,
        new CompatibilityDomain("fabric", "fabric", "26.2", 2, "sha256:mods"),
        "example:nested", 7, ItemEnvelope.PayloadEncoding.RAW,
        new byte[]{0, 1, -1}, ItemEnvelopeService.sha256(new byte[]{0, 1, -1}),
        Map.of("name", "Nested"), Instant.parse("2026-08-23T00:00:00.123456789Z"));
    ItemEnvelopeBinaryCodec codec = new ItemEnvelopeBinaryCodec();
    ItemEnvelope output = codec.decode(codec.encode(input));
    assertEquals(input.schemaVersion(), output.schemaVersion());
    assertEquals(input.compatibilityDomain(), output.compatibilityDomain());
    assertEquals(input.summary(), output.summary());
    assertEquals(input.createdAt(), output.createdAt());
    assertArrayEquals(input.payload(), output.payload());
    byte[] invalid = java.util.Arrays.copyOf(codec.encode(input), codec.encode(input).length + 1);
    assertThrows(IllegalArgumentException.class, () -> codec.decode(invalid));
  }
}
