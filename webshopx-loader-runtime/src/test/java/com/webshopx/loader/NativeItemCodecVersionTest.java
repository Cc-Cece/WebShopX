package com.webshopx.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.webshopx.platform.PlatformIdentity;
import java.time.Clock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NativeItemCodecVersionTest {
  @ParameterizedTest
  @CsvSource({
      "1.18.2, 1",
      "1.20.4, 1",
      "1.20.5, 2",
      "1.20.6, 2",
      "1.21, 2",
      "1.21.11, 2",
      "26.1, 2"
  })
  void selectsTheDataComponentCodecAtTheMinecraftBoundary(String minecraft, int expected) {
    PlatformIdentity identity =
        new PlatformIdentity("fabric", "fabric", minecraft, "test", "server", "sha256:test");

    NativeItemCodec codec = new NativeItemCodec(identity, () -> null, Clock.systemUTC());

    assertEquals(expected, codec.version());
  }
}
