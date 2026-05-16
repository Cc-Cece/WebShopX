package com.webshopx.neoforge;

import com.webshopx.core.M2RuntimeBootstrap;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(WebShopXNeoForgeMod.MOD_ID)
public final class WebShopXNeoForgeMod {

  public static final String MOD_ID = "webshopx_neoforge";
  private static final Logger LOGGER = LoggerFactory.getLogger("WebShopX/NeoForge");
  private static final String RUNTIME_LOADER = "neoforge";
  private static final String DEFAULT_RUNTIME_LINE = "1.21.x";
  private static final AtomicReference<M2RuntimeBootstrap.RuntimeHandle> RUNTIME_HANDLE = new AtomicReference<>();

  public WebShopXNeoForgeMod() {
    bootstrapRuntime("neoforge-loader");
    registerCommandContract();
  }

  public static void main(String[] args) {
    bootstrapRuntime("neoforge-smoke-main");
  }

  private static void registerCommandContract() {
    LOGGER.info("[M2] reserved NeoForge command contract: /webshopx");
  }

  private static void bootstrapRuntime(String source) {
    try {
      String runtimeId = runtimeId();
      M2RuntimeBootstrap.RuntimeHandle runtimeHandle = M2RuntimeBootstrap.start(
          runtimeId,
          resolveVersion(),
          Path.of("build", "m2-runtime"),
          LOGGER::info);
      M2RuntimeBootstrap.RuntimeHandle previous = RUNTIME_HANDLE.getAndSet(runtimeHandle);
      if (previous != null) {
        previous.close();
      }
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        M2RuntimeBootstrap.RuntimeHandle handle = RUNTIME_HANDLE.getAndSet(null);
        if (handle != null) {
          handle.close();
        }
      }, "webshopx-neoforge-m2-shutdown"));
      LOGGER.info("[M2] source={} runtime={} version={} endpoint={}",
          source,
          runtimeHandle.runtimeId(),
          runtimeHandle.version(),
          runtimeHandle.endpoint());
    } catch (Exception exception) {
      LOGGER.error("[M2] NeoForge runtime bootstrap failed", exception);
      throw new IllegalStateException("NeoForge M2 bootstrap failed", exception);
    }
  }

  private static String resolveVersion() {
    Package selfPackage = WebShopXNeoForgeMod.class.getPackage();
    String implementationVersion = selfPackage == null ? null : selfPackage.getImplementationVersion();
    return implementationVersion == null || implementationVersion.isBlank() ? "dev" : implementationVersion;
  }

  private static String runtimeId() {
    return RUNTIME_LOADER + "-" + resolveRuntimeLine();
  }

  private static String resolveRuntimeLine() {
    String declared = System.getProperty("webshopx.runtime.line", DEFAULT_RUNTIME_LINE);
    String trimmed = declared == null ? "" : declared.trim();
    if ("1.20.6".equals(trimmed) || "1.21.x".equals(trimmed)) {
      return trimmed;
    }
    return DEFAULT_RUNTIME_LINE;
  }
}
