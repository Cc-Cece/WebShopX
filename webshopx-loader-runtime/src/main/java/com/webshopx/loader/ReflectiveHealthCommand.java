package com.webshopx.loader;

import com.webshopx.ServiceException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.function.Consumer;

/** Registers the read-only health command without leaking Loader or Minecraft types into core. */
public final class ReflectiveHealthCommand {
  private ReflectiveHealthCommand() { }

  public static boolean installEventBus(String holderClassName) {
    try {
      Class<?> holder = Class.forName(holderClassName);
      var eventBusField = holder.getField("EVENT_BUS");
      Object bus = eventBusField.get(null);
      Method typedListener = Arrays.stream(eventBusField.getType().getMethods())
          .filter(method -> method.getName().equals("addListener"))
          .filter(method -> method.getParameterCount() == 4)
          .filter(method -> method.getParameterTypes()[2] == Class.class)
          .filter(method -> Consumer.class.isAssignableFrom(method.getParameterTypes()[3]))
          .findFirst().orElse(null);
      Consumer<Object> listener = event -> {
        if (event.getClass().getSimpleName().equals("RegisterCommandsEvent")) register(event);
      };
      if (typedListener != null) {
        Class<?> priorityType = typedListener.getParameterTypes()[0];
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object normal = Enum.valueOf((Class<? extends Enum>) priorityType, "NORMAL");
        String eventName = holderClassName.startsWith("net.minecraftforge.")
            ? "net.minecraftforge.event.RegisterCommandsEvent"
            : "net.neoforged.neoforge.event.RegisterCommandsEvent";
        typedListener.invoke(bus, normal, false, Class.forName(eventName), listener);
      } else {
        Method addListener = Arrays.stream(eventBusField.getType().getMethods())
            .filter(method -> method.getName().equals("addListener"))
            .filter(method -> method.getParameterCount() == 1)
            .filter(method -> Consumer.class.isAssignableFrom(method.getParameterTypes()[0]))
            .findFirst().orElseThrow(() -> new NoSuchMethodException("addListener"));
        addListener.invoke(bus, listener);
      }
      return true;
    } catch (ReflectiveOperationException | LinkageError unavailable) {
      Throwable detail = unavailable instanceof java.lang.reflect.InvocationTargetException invocation
          && invocation.getCause() != null ? invocation.getCause() : unavailable;
      System.out.printf("[WebShopX] native health command unavailable: %s: %s%n",
          detail.getClass().getSimpleName(), detail.getMessage());
      return false;
    }
  }

  public static boolean installFabric() {
    String[] callbacks = {
        "net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback",
        "net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback"
    };
    for (String callbackName : callbacks) {
      try {
        Class<?> callbackType = Class.forName(callbackName);
        var eventField = callbackType.getField("EVENT");
        Object event = eventField.get(null);
        Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
            (proxy, method, arguments) -> {
              if (method.getName().equals("register") && arguments != null && arguments.length > 0) {
                registerDispatcher(arguments[0]);
              }
              return null;
            });
        Method register = Arrays.stream(eventField.getType().getMethods())
            .filter(method -> method.getName().equals("register") && method.getParameterCount() == 1)
            .findFirst().orElseThrow();
        register.invoke(event, callback);
        return true;
      } catch (ClassNotFoundException missingVersion) {
        // Try the other supported Fabric command API generation.
      } catch (ReflectiveOperationException | LinkageError unavailable) {
        System.out.printf("[WebShopX] Fabric health command unavailable: %s%n", unavailable);
        return false;
      }
    }
    System.out.println("[WebShopX] Fabric health command unavailable: Fabric API is missing");
    return false;
  }

  private static void register(Object event) {
    try {
      Object dispatcher = event.getClass().getMethod("getDispatcher").invoke(event);
      registerDispatcher(dispatcher);
    } catch (ReflectiveOperationException | LinkageError failure) {
      throw new IllegalStateException("cannot register WebShopX health command", failure);
    }
  }

  private static void registerDispatcher(Object dispatcher) {
    try {
      Class<?> literalBuilder = Class.forName("com.mojang.brigadier.builder.LiteralArgumentBuilder");
      Object builder = literalBuilder.getMethod("literal", String.class).invoke(null, "webshopx-health");
      Class<?> commandType = Class.forName("com.mojang.brigadier.Command");
      Object command = Proxy.newProxyInstance(commandType.getClassLoader(), new Class<?>[]{commandType},
          (proxy, method, arguments) -> {
            if (method.getName().equals("run")) {
              Object context = arguments[0];
              Object source = context.getClass().getMethod("getSource").invoke(context);
              send(source, LoaderRuntime.healthLine());
              return 1;
            }
            return null;
          });
      builder.getClass().getMethod("executes", commandType).invoke(builder, command);
      Method register = Arrays.stream(dispatcher.getClass().getMethods())
          .filter(method -> method.getName().equals("register") && method.getParameterCount() == 1)
          .findFirst().orElseThrow();
      register.invoke(dispatcher, builder);
      registerBusinessCommands(dispatcher, register, literalBuilder, commandType);
    } catch (ReflectiveOperationException | LinkageError failure) {
      throw new IllegalStateException("cannot register WebShopX health command", failure);
    }
  }

  private static void registerBusinessCommands(
      Object dispatcher, Method register, Class<?> literalBuilder, Class<?> commandType)
      throws ReflectiveOperationException {
    register.invoke(dispatcher, command(
        literalBuilder, commandType, "webshopx-item-probe", null,
        invocation -> LoaderRuntime.nativeItemProbe()));
    register.invoke(dispatcher, command(
        literalBuilder, commandType, "webshopx-balance", null,
        invocation -> {
          var player = requirePlayer(invocation.source());
          var user = LoaderRuntime.administration().orElseThrow()
              .lookupUser(player.id().toString())
              .orElseThrow(() -> new ServiceException("not_bound", "Set a WebShopX password first"));
          return "WebShopX balance shopCoin=" + user.shopCoin() + " gameCoin=" + user.gameCoin();
        }));
    register.invoke(dispatcher, command(
        literalBuilder, commandType, "webshopx-password", "password",
        invocation -> {
          var player = requirePlayer(invocation.source());
          var result = LoaderRuntime.authentication().orElseThrow()
              .setPasswordFromGame(player.id(), player.name(), invocation.argument());
          return "WebShopX password updated userId=" + result.userId();
        }));
    register.invoke(dispatcher, command(
        literalBuilder, commandType, "webshopx-redeem", "code",
        invocation -> {
          var player = requirePlayer(invocation.source());
          var user = LoaderRuntime.administration().orElseThrow()
              .lookupUser(player.id().toString())
              .orElseThrow(() -> new ServiceException("not_bound", "Set a WebShopX password first"));
          var result = LoaderRuntime.redeemCodes().orElseThrow().redeem(user.userId(), invocation.argument());
          return "WebShopX redeem status=" + result.status() + " shopCoin="
              + result.balance().shopCoin() + " gameCoin=" + result.balance().gameCoin();
        }));
  }

  private static Object command(
      Class<?> literalBuilder, Class<?> commandType, String name, String argumentName,
      NativeCommand action) throws ReflectiveOperationException {
    Object literal = literalBuilder.getMethod("literal", String.class).invoke(null, name);
    Object target = literal;
    if (argumentName != null) {
      Class<?> argumentType = Class.forName("com.mojang.brigadier.arguments.ArgumentType");
      Class<?> stringType = Class.forName("com.mojang.brigadier.arguments.StringArgumentType");
      Object word = stringType.getMethod("word").invoke(null);
      Class<?> required = Class.forName("com.mojang.brigadier.builder.RequiredArgumentBuilder");
      target = required.getMethod("argument", String.class, argumentType)
          .invoke(null, argumentName, word);
    }
    String capturedArgument = argumentName;
    Object handler = Proxy.newProxyInstance(commandType.getClassLoader(), new Class<?>[]{commandType},
        (proxy, method, arguments) -> {
          if (!method.getName().equals("run")) return null;
          Object context = arguments[0];
          Object source = context.getClass().getMethod("getSource").invoke(context);
          String value = capturedArgument == null ? null
              : (String) context.getClass().getMethod("getArgument", String.class, Class.class)
                  .invoke(context, capturedArgument, String.class);
          try {
            send(source, action.execute(new NativeInvocation(source, value)));
            return 1;
          } catch (ServiceException expected) {
            send(source, "WebShopX error=" + expected.code() + " message=" + expected.getMessage());
            return 0;
          } catch (RuntimeException failure) {
            send(source, "WebShopX error=internal_error");
            System.err.printf("[WebShopX] native command %s failed: %s%n", name, failure);
            return 0;
          }
        });
    target.getClass().getMethod("executes", commandType).invoke(target, handler);
    if (argumentName != null) {
      Class<?> argumentBuilder = Class.forName("com.mojang.brigadier.builder.ArgumentBuilder");
      literal.getClass().getMethod("then", argumentBuilder).invoke(literal, target);
    }
    return literal;
  }

  private static com.webshopx.platform.PlatformPorts.PlayerSnapshot requirePlayer(Object source) {
    String serverId = LoaderRuntime.active().map(runtime -> runtime.platform().identity().serverId())
        .orElse("standalone");
    return NativePlayerDirectory.commandSourcePlayer(source, serverId)
        .orElseThrow(() -> new ServiceException("player_only", "This command requires a player"));
  }

  @FunctionalInterface
  private interface NativeCommand { String execute(NativeInvocation invocation); }
  private record NativeInvocation(Object source, String argument) { }

  private static void send(Object source, String message) {
    System.out.println(message);
    try {
      Class<?> component = Class.forName("net.minecraft.network.chat.Component");
      Object value = component.getMethod("literal", String.class).invoke(null, message);
      Method send = Arrays.stream(source.getClass().getMethods())
          .filter(method -> method.getName().equals("sendSystemMessage") && method.getParameterCount() == 1)
          .findFirst().orElseThrow();
      send.invoke(source, value);
    } catch (ReflectiveOperationException | LinkageError unavailable) {
      // Console output above is the portable fallback for remapped server methods.
    }
  }
}
