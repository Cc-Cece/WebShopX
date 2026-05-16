package com.webshopx.neoforge;

import com.webshopx.core.M2RuntimeBootstrap;
import java.nio.file.Path;

public final class WebShopXNeoForgeSmoke {

  private static final String DEFAULT_RUNTIME_LINE = "1.21.x";

  private WebShopXNeoForgeSmoke() {
  }

  public static void main(String[] args) throws Exception {
    String runtimeLine = resolveRuntimeLine(args);
    M2RuntimeBootstrap.RuntimeHandle runtimeHandle = M2RuntimeBootstrap.start(
        "neoforge-" + runtimeLine,
        resolveVersion(),
        Path.of("build", "m2-runtime"),
        System.out::println);
    System.out.println("[M2] NeoForge smoke line=" + runtimeLine + " endpoint=" + runtimeHandle.endpoint());
    runtimeHandle.close();
  }

  private static String resolveVersion() {
    Package selfPackage = WebShopXNeoForgeSmoke.class.getPackage();
    String implementationVersion = selfPackage == null ? null : selfPackage.getImplementationVersion();
    return implementationVersion == null || implementationVersion.isBlank() ? "dev" : implementationVersion;
  }

  private static String resolveRuntimeLine(String[] args) {
    if (args != null && args.length > 0) {
      String candidate = args[0] == null ? "" : args[0].trim();
      if ("1.20.6".equals(candidate) || "1.21.x".equals(candidate)) {
        return candidate;
      }
    }
    String declared = System.getProperty("webshopx.runtime.line", DEFAULT_RUNTIME_LINE);
    String trimmed = declared == null ? "" : declared.trim();
    if ("1.20.6".equals(trimmed) || "1.21.x".equals(trimmed)) {
      return trimmed;
    }
    return DEFAULT_RUNTIME_LINE;
  }
}
