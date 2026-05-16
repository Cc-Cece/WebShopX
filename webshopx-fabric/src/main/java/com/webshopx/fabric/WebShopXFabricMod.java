package com.webshopx.fabric;

import com.webshopx.core.M0RuntimeBootstrap;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WebShopXFabricMod implements ModInitializer {

  private static final Logger LOGGER = LoggerFactory.getLogger("WebShopX/Fabric");
  private static final String MOD_ID = "webshopx";
  private static final String RUNTIME_ID = "fabric";

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
      M0RuntimeBootstrap.RuntimeHandle runtimeHandle = M0RuntimeBootstrap.start(
          RUNTIME_ID,
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
      LOGGER.error("[M0] Fabric runtime bootstrap failed", exception);
      throw new IllegalStateException("Fabric M0 bootstrap failed", exception);
    }
  }

  private static String resolveVersion() {
    Package selfPackage = WebShopXFabricMod.class.getPackage();
    String implementationVersion = selfPackage == null ? null : selfPackage.getImplementationVersion();
    return implementationVersion == null || implementationVersion.isBlank() ? "dev" : implementationVersion;
  }

  private static void registerCommandBridge() {
    try {
      ClassLoader classLoader = WebShopXFabricMod.class.getClassLoader();
      Class<?> callbackClass = Class.forName(
          "net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback",
          true,
          classLoader);
      Object event = callbackClass.getField("EVENT").get(null);
      Method registerMethod = event.getClass().getMethod("register", callbackClass);
      Object callbackProxy = Proxy.newProxyInstance(classLoader, new Class[] {callbackClass}, (proxy, method, args) -> {
        if ("register".equals(method.getName()) && args != null && args.length >= 1) {
          registerCommand(args[0]);
        }
        return null;
      });
      registerMethod.invoke(event, callbackProxy);
      LOGGER.info("[M0] registered Fabric command bridge: /webshopx-m0-health");
    } catch (ClassNotFoundException ignored) {
      LOGGER.info("[M0] Fabric command API not found; command bridge skipped in this runtime");
    } catch (Exception exception) {
      LOGGER.warn("[M0] Failed to register Fabric command bridge", exception);
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
    Object literalBuilder = literalFactory.invoke(null, "webshopx-m0-health");

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
