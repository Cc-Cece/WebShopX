package com.webshopx.fabric;

import com.webshopx.core.M2RuntimeBootstrap;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WebShopXFabricMod implements ModInitializer {

  private static final Logger LOGGER = LoggerFactory.getLogger("WebShopX/Fabric");
  private static final String RUNTIME_LOADER = "fabric";
  private static final String DEFAULT_RUNTIME_LINE = "1.21.x";
  private static final AtomicReference<M2RuntimeBootstrap.RuntimeHandle> RUNTIME_HANDLE = new AtomicReference<>();

  @Override
  public void onInitialize() {
    bootstrapRuntime("fabric-loader");
    registerCommandBridge();
  }

  public static void main(String[] args) {
    bootstrapRuntime("fabric-smoke-main");
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
      }, "webshopx-fabric-m2-shutdown"));
      LOGGER.info("[M2] source={} runtime={} version={} endpoint={}",
          source,
          runtimeHandle.runtimeId(),
          runtimeHandle.version(),
          runtimeHandle.endpoint());
    } catch (Exception exception) {
      LOGGER.error("[M2] Fabric runtime bootstrap failed", exception);
      throw new IllegalStateException("Fabric M2 bootstrap failed", exception);
    }
  }

  private static String resolveVersion() {
    Package selfPackage = WebShopXFabricMod.class.getPackage();
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

  private static void registerCommandBridge() {
    try {
      ClassLoader classLoader = WebShopXFabricMod.class.getClassLoader();
      Class<?> callbackClass = Class.forName(
          "net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback",
          true,
          classLoader);
      Object event = callbackClass.getField("EVENT").get(null);
      Method registerMethod = Arrays.stream(event.getClass().getMethods())
          .filter(method -> "register".equals(method.getName()) && method.getParameterCount() == 1)
          .findFirst()
          .orElseThrow(() -> new NoSuchMethodException("register(listener)"));
      Object callbackProxy = Proxy.newProxyInstance(classLoader, new Class[] {callbackClass}, (proxy, method, args) -> {
        if ("register".equals(method.getName()) && args != null && args.length >= 1) {
          registerCommand(args[0]);
        }
        return null;
      });
      registerMethod.invoke(event, callbackProxy);
      LOGGER.info("[M2] registered Fabric command bridge: /webshopx");
    } catch (ClassNotFoundException ignored) {
      LOGGER.info("[M2] Fabric command API not found; command bridge skipped in this runtime");
    } catch (Exception exception) {
      LOGGER.warn("[M2] Failed to register Fabric command bridge", exception);
    }
  }

  private static void registerCommand(Object dispatcher) throws Exception {
    ClassLoader classLoader = dispatcher.getClass().getClassLoader();
    Class<?> literalBuilderClass = Class.forName(
        "com.mojang.brigadier.builder.LiteralArgumentBuilder",
        false,
        classLoader);
    Class<?> commandClass = Class.forName("com.mojang.brigadier.Command", false, classLoader);

    Method literalFactory = literalBuilderClass.getMethod("literal", String.class);
    Object literalBuilder = literalFactory.invoke(null, "webshopx");

    Object commandProxy = Proxy.newProxyInstance(classLoader, new Class[] {commandClass}, (proxy, method, args) -> {
      if ("run".equals(method.getName())) {
        return 1;
      }
      return null;
    });

    Method executesMethod = literalBuilder.getClass().getMethod("executes", commandClass);
    executesMethod.invoke(literalBuilder, commandProxy);

    Method registerMethod = dispatcher.getClass().getMethod("register", literalBuilderClass);
    registerMethod.invoke(dispatcher, literalBuilder);
  }
}
