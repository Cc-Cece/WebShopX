package com.webshopx.loader;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Consumer;

/** Connects Loader server and connection events to the platform-neutral runtime. */
final class ReflectiveNativeLifecycle {
  private ReflectiveNativeLifecycle() { }

  static boolean installFabric() {
    boolean started = registerFabric(
        "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStarted",
        "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents", "SERVER_STARTED",
        (method, arguments) -> {
          LoaderRuntime.nativeServerStarted(arguments[0]);
          return null;
        });
    boolean stopping = registerFabric(
        "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStopping",
        "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents", "SERVER_STOPPING",
        (method, arguments) -> {
          LoaderRuntime.nativeServerStopping();
          return null;
        });
    registerFabric(
        "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents$Join",
        "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents", "JOIN",
        (method, arguments) -> {
          LoaderRuntime.nativePlayerJoined(arguments[0]);
          return null;
        });
    registerFabric(
        "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents$Disconnect",
        "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents", "DISCONNECT",
        (method, arguments) -> {
          LoaderRuntime.nativePlayerDisconnected(arguments[0]);
          return null;
        });
    registerFabric(
        "net.fabricmc.fabric.api.event.player.UseBlockCallback",
        "net.fabricmc.fabric.api.event.player.UseBlockCallback", "EVENT",
        (method, arguments) -> {
          Object position = invokeNoArg(arguments[3], "getBlockPos", "method_17777", "m_82425_");
          boolean denied = LoaderRuntime.nativeSupplyAccessDenied(
              arguments[0], arguments[1], position, NativeSupplyProtection.Action.INTERACT);
          return NativeSupplyProtection.enumResult(method.getReturnType(), denied ? "FAIL" : "PASS");
        });
    registerFabric(
        "net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents$Before",
        "net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents", "BEFORE",
        (method, arguments) -> !LoaderRuntime.nativeSupplyAccessDenied(
            arguments[1], arguments[0], arguments[2], NativeSupplyProtection.Action.BREAK));
    return started && stopping;
  }

  static boolean installEventBus(String holderClassName) {
    String prefix = holderClassName.startsWith("net.minecraftforge.") ? "net.minecraftforge." : "net.neoforged.neoforge.";
    boolean started = registerEventBus(holderClassName, prefix + "event.server.ServerStartedEvent",
        event -> LoaderRuntime.nativeServerStarted(invokeNoArg(event, "getServer")));
    boolean stopping = registerEventBus(holderClassName, prefix + "event.server.ServerStoppingEvent",
        event -> LoaderRuntime.nativeServerStopping());
    registerEventBus(holderClassName, prefix + "event.entity.player.PlayerEvent$PlayerLoggedInEvent",
        LoaderRuntime::nativePlayerJoined);
    registerEventBus(holderClassName, prefix + "event.entity.player.PlayerEvent$PlayerLoggedOutEvent",
        LoaderRuntime::nativePlayerDisconnected);
    registerEventBus(holderClassName, prefix + "event.entity.player.PlayerInteractEvent$RightClickBlock",
        ReflectiveNativeLifecycle::protectEventInteraction);
    registerEventBus(holderClassName, prefix + "event.level.BlockEvent$BreakEvent",
        ReflectiveNativeLifecycle::protectEventBreak);
    registerEventBus(holderClassName, prefix + "event.level.ExplosionEvent$Detonate",
        ReflectiveNativeLifecycle::protectExplosion);
    if (holderClassName.startsWith("net.minecraftforge.")) {
      registerEventBus(holderClassName, prefix + "event.world.BlockEvent$BreakEvent",
          ReflectiveNativeLifecycle::protectEventBreak);
      registerEventBus(holderClassName, prefix + "event.world.ExplosionEvent$Detonate",
          ReflectiveNativeLifecycle::protectExplosion);
    }
    return started && stopping;
  }

  private static boolean registerFabric(
      String callbackClass, String holderClass, String fieldName, Invocation invocation) {
    try {
      Class<?> callbackType = Class.forName(callbackClass);
      var eventField = Class.forName(holderClass).getField(fieldName);
      Object event = eventField.get(null);
      Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
          (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) {
              return switch (method.getName()) {
                case "toString" -> "WebShopX-" + callbackType.getSimpleName();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> null;
              };
            }
            return invocation.accept(method, arguments == null ? new Object[0] : arguments);
          });
      Method register = Arrays.stream(eventField.getType().getMethods())
          .filter(method -> method.getName().equals("register") && method.getParameterCount() == 1)
          .findFirst().orElseThrow();
      register.invoke(event, callback);
      return true;
    } catch (ReflectiveOperationException | LinkageError unavailable) {
      Throwable detail = unavailable instanceof java.lang.reflect.InvocationTargetException invocationFailure
          && invocationFailure.getCause() != null ? invocationFailure.getCause() : unavailable;
      System.out.printf("[WebShopX] Fabric native callback unavailable type=%s detail=%s: %s%n",
          callbackClass, detail.getClass().getSimpleName(), detail.getMessage());
      return false;
    }
  }

  private static boolean registerEventBus(String holderClassName, String eventClassName, Consumer<Object> listener) {
    try {
      var eventBusField = Class.forName(holderClassName).getField("EVENT_BUS");
      Object bus = eventBusField.get(null);
      Method method = Arrays.stream(eventBusField.getType().getMethods())
          .filter(candidate -> candidate.getName().equals("addListener"))
          .filter(candidate -> candidate.getParameterCount() == 4)
          .filter(candidate -> candidate.getParameterTypes()[2] == Class.class)
          .filter(candidate -> Consumer.class.isAssignableFrom(candidate.getParameterTypes()[3]))
          .findFirst().orElseThrow();
      Class<?> priorityType = method.getParameterTypes()[0];
      @SuppressWarnings({"rawtypes", "unchecked"})
      Object normal = Enum.valueOf((Class<? extends Enum>) priorityType, "NORMAL");
      method.invoke(bus, normal, false, Class.forName(eventClassName), listener);
      return true;
    } catch (ReflectiveOperationException | LinkageError unavailable) {
      Throwable detail = unavailable instanceof java.lang.reflect.InvocationTargetException invocationFailure
          && invocationFailure.getCause() != null ? invocationFailure.getCause() : unavailable;
      System.out.printf("[WebShopX] event-bus callback unavailable type=%s detail=%s: %s%n",
          eventClassName, detail.getClass().getSimpleName(), detail.getMessage());
      return false;
    }
  }

  private static void protectEventInteraction(Object event) {
    Object player = invokeNoArg(event, "getEntity", "getPlayer");
    Object level = invokeNoArg(event, "getLevel", "getWorld");
    Object position = invokeNoArg(event, "getPos");
    if (LoaderRuntime.nativeSupplyAccessDenied(
        player, level, position, NativeSupplyProtection.Action.INTERACT)) {
      invokeOneArg(event, "setCanceled", true);
    }
  }

  private static void protectEventBreak(Object event) {
    Object player = invokeNoArg(event, "getPlayer");
    Object level = invokeNoArg(event, "getLevel", "getWorld");
    Object position = invokeNoArg(event, "getPos");
    if (LoaderRuntime.nativeSupplyAccessDenied(
        player, level, position, NativeSupplyProtection.Action.BREAK)) {
      invokeOneArg(event, "setCanceled", true);
    }
  }

  private static void protectExplosion(Object event) {
    Object level = invokeNoArg(event, "getLevel", "getWorld");
    Object affected = invokeNoArg(event, "getAffectedBlocks");
    if (affected instanceof Collection<?> blocks) {
      blocks.removeIf(position -> LoaderRuntime.nativeSupplyAccessDenied(
          null, level, position, NativeSupplyProtection.Action.EXPLOSION));
    }
  }

  private static Object invokeNoArg(Object target, String... names) {
    try {
      Method method = Arrays.stream(target.getClass().getMethods())
          .filter(candidate -> candidate.getParameterCount() == 0)
          .filter(candidate -> Arrays.asList(names).contains(candidate.getName()))
          .findFirst().orElseThrow();
      return method.invoke(target);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(
          "native event is missing " + String.join("/", names), failure);
    }
  }

  private static void invokeOneArg(Object target, String name, Object value) {
    try {
      Method method = Arrays.stream(target.getClass().getMethods())
          .filter(candidate -> candidate.getName().equals(name))
          .filter(candidate -> candidate.getParameterCount() == 1)
          .findFirst().orElseThrow();
      method.invoke(target, value);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("native event is missing " + name, failure);
    }
  }

  @FunctionalInterface
  private interface Invocation { Object accept(Method method, Object[] arguments); }
}
