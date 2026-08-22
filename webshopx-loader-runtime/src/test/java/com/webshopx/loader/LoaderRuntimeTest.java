package com.webshopx.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.webshopx.core.WebShopXCoreRuntime;
import com.webshopx.platform.CapabilitySnapshot.Capability;
import com.webshopx.platform.CapabilitySnapshot.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class LoaderRuntimeTest {
  @TempDir Path temporaryDirectory;
  @AfterEach void stop() { LoaderRuntime.stop(); }

  @Test void startsOnceAndFailsClosedForUnimplementedCapabilities() {
    System.setProperty("webshopx.data-dir", temporaryDirectory.toString());
    WebShopXCoreRuntime first = LoaderRuntime.start("fabric", "1.20.1", "0.19.3");
    WebShopXCoreRuntime second = LoaderRuntime.start("fabric", "1.20.1", "0.19.3");
    assertEquals(first, second);
    assertEquals(WebShopXCoreRuntime.State.READY, first.state());
    assertEquals(Status.UNAVAILABLE, first.platform().capabilities().state(Capability.ECONOMY).status());
    LoaderRuntime.stop();
    assertFalse(LoaderRuntime.active().isPresent());
  }
}
