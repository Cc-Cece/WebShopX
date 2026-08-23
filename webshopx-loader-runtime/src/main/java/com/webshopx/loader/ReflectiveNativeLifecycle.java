package com.webshopx.loader;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.function.Consumer;

/** Connects Loader server and connection events to the platform-neutral runtime. */
final class ReflectiveNativeLifecycle {
  private ReflectiveNativeLifecycle() { }

  static boolean installFabric() {
    boolean started = registerFabric(
        "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStarted",
        "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents", "SERVER_STARTED",
        arguments -> LoaderRuntime.nativeServerStarted(arguments[0]));
    boolean stopping = registerFabric(
        "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStopping",
        "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents", "SERVER_STOPPING",
        arguments -> LoaderRuntime.nativeServerStopping());
    registerFabric(
        "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents$Join",
        "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents", "JOIN",
        arguments -> LoaderRuntime.nativePlayerJoined(arguments[0]));
    registerFabric(
        "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents$Disconnect",
        "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents", "DISCONNECT",
        arguments -> LoaderRuntime.nativePlayerDisconnected(arguments[0]));
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
            if (!method.getName().equals("toString") && arguments != null) invocation.accept(arguments);
            return null;
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

  private static Object invokeNoArg(Object target, String name) {
    try {
      return target.getClass().getMethod(name).invoke(target);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("native event is missing " + name, failure);
    }
  }

  @FunctionalInterface
  private interface Invocation { void accept(Object[] arguments); }
}
