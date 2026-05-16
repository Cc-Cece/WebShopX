package com.webshopx.neoforge;

import com.webshopx.core.M0RuntimeBootstrap;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WebShopXNeoForgeMod {

  public static final String MOD_ID = "webshopx_m0_neoforge";
  private static final Logger LOGGER = LoggerFactory.getLogger("WebShopX/NeoForge");

  private WebShopXNeoForgeMod() {
  }

  public static void onLoaderInitialize() {
    bootstrapRuntime("neoforge-loader");
    registerCommandContract();
  }

  public static void main(String[] args) {
    bootstrapRuntime("neoforge-smoke-main");
  }

  private static void registerCommandContract() {
    LOGGER.info("[M0] reserved NeoForge command contract: /webshopx-m0-health");
  }

  private static void bootstrapRuntime(String source) {
    try {
      M0RuntimeBootstrap.RuntimeHandle runtimeHandle = M0RuntimeBootstrap.start(
          "neoforge",
          resolveVersion(),
          Path.of("build", "m0-runtime"),
          LOGGER::info);
      LOGGER.info("[M0] source={} runtime={} version={} health={}",
          source,
          runtimeHandle.runtimeId(),
          runtimeHandle.version(),
          runtimeHandle.healthEndpoint());
      runtimeHandle.close();
    } catch (Exception exception) {
      LOGGER.error("[M0] NeoForge runtime bootstrap failed", exception);
      throw new IllegalStateException("NeoForge M0 bootstrap failed", exception);
    }
  }

  private static String resolveVersion() {
    Package selfPackage = WebShopXNeoForgeMod.class.getPackage();
    String implementationVersion = selfPackage == null ? null : selfPackage.getImplementationVersion();
    return implementationVersion == null || implementationVersion.isBlank() ? "dev" : implementationVersion;
  }
}
