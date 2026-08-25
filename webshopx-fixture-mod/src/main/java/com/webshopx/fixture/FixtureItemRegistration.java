package com.webshopx.fixture;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Reflection-only registration so one controlled fixture can cover each frozen Loader range. */
final class FixtureItemRegistration {
  private static final String MOD_ID = "webshopx_fixture";
  private static final String ITEM_NAME = "data_item";

  private FixtureItemRegistration() { }

  static void registerDirect() {
    try {
      RegistryHandle handle = registryHandle();
      Object item = createItem(handle);
      Method register = staticRegister(handle, item);
      register.invoke(null, handle.registry(), handle.location(), item);
      verify(handle, item);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
      throw new IllegalStateException("cannot register controlled Fabric fixture item", root(failure));
    }
  }

  static void registerDeferred(String namespace) {
    registerDeferred(namespace, modEventBus(namespace));
  }

  static void registerDeferred(String namespace, Object modBus) {
    try {
      RegistryHandle handle = registryHandle();
      Class<?> deferredType = Class.forName(
          namespace + (namespace.endsWith("minecraftforge")
              ? ".registries.DeferredRegister" : ".neoforge.registries.DeferredRegister"));
      Object deferred = createDeferred(deferredType, handle);
      Method registerEntry = Arrays.stream(deferredType.getMethods())
          .filter(method -> method.getName().equals("register") && method.getParameterCount() == 2)
          .filter(method -> method.getParameterTypes()[0] == String.class)
          .filter(method -> Supplier.class.isAssignableFrom(method.getParameterTypes()[1]))
          .findFirst().orElseThrow(() -> new NoSuchMethodException("DeferredRegister.register"));
      registerEntry.invoke(deferred, ITEM_NAME, (Supplier<Object>) () -> {
        try {
          return createItem(handle);
        } catch (ReflectiveOperationException failure) {
          throw new IllegalStateException("cannot create fixture item", failure);
        }
      });
      Method registerBus = Arrays.stream(deferredType.getMethods())
          .filter(method -> method.getName().equals("register") && method.getParameterCount() == 1)
          .filter(method -> method.getParameterTypes()[0].isInstance(modBus))
          .findFirst().orElseThrow(() -> new NoSuchMethodException("DeferredRegister.register(bus)"));
      registerBus.invoke(deferred, modBus);
      System.out.println("[WebShopX Fixture] scheduled registry=" + MOD_ID + ":" + ITEM_NAME);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
      throw new IllegalStateException("cannot register controlled Loader fixture item", root(failure));
    }
  }

  private static Object modEventBus(String namespace) {
    try {
      Class<?> contextType = Class.forName(namespace + ".fml.javafmlmod.FMLJavaModLoadingContext");
      Object context = contextType.getMethod("get").invoke(null);
      return contextType.getMethod("getModEventBus").invoke(context);
    } catch (ReflectiveOperationException | RuntimeException failure) {
      throw new IllegalStateException("cannot acquire Loader mod event bus", root(failure));
    }
  }

  private static Object createDeferred(Class<?> deferredType, RegistryHandle handle)
      throws ReflectiveOperationException {
    for (Method method : deferredType.getMethods()) {
      if (!Modifier.isStatic(method.getModifiers()) || !method.getName().equals("createItems")) continue;
      if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == String.class) {
        return method.invoke(null, MOD_ID);
      }
    }
    for (Object registryArgument : new Object[]{handle.registryKey(), handle.registry()}) {
      if (registryArgument == null) continue;
      for (Method method : deferredType.getMethods()) {
        if (!Modifier.isStatic(method.getModifiers()) || !method.getName().equals("create")) continue;
        if (method.getParameterCount() != 2 || method.getParameterTypes()[1] != String.class) continue;
        if (!method.getParameterTypes()[0].isInstance(registryArgument)) continue;
        return method.invoke(null, registryArgument, MOD_ID);
      }
    }
    throw new NoSuchMethodException("DeferredRegister.create");
  }

  private static RegistryHandle registryHandle() throws ReflectiveOperationException {
    ClassLoader loader = FixtureItemRegistration.class.getClassLoader();
    Class<?> itemType = loadFirst(loader, "net.minecraft.world.item.Item", "net.minecraft.class_1792");
    Class<?> registryType = loadFirst(loader, "net.minecraft.core.Registry", "net.minecraft.class_2378");
    Object location = location(loader, MOD_ID, ITEM_NAME);
    Object stoneLocation = location(loader, "minecraft", "stone");
    for (String holderName : List.of("net.minecraft.core.registries.BuiltInRegistries",
        "net.minecraft.core.Registry", "net.minecraft.class_7923", "net.minecraft.class_2378")) {
      Class<?> holder;
      try {
        holder = Class.forName(holderName, true, loader);
      } catch (ClassNotFoundException missing) {
        continue;
      }
      try {
        Field itemField = holder.getField("ITEM");
        Object itemRegistry = itemField.get(null);
        if (itemRegistry != null) {
          Object registryKey = registryKey(itemRegistry);
          return new RegistryHandle(itemRegistry, registryKey, location,
              resourceKey(loader, registryKey, location), itemType);
        }
      } catch (NoSuchFieldException ignored) {
        // Older official and intermediary runtimes require structural discovery below.
      }
      for (Field field : holder.getFields()) {
        if (!Modifier.isStatic(field.getModifiers())) continue;
        Object candidate;
        try {
          candidate = field.get(null);
        } catch (RuntimeException ignored) {
          continue;
        }
        if (candidate == null || !registryType.isInstance(candidate)) continue;
        boolean typedItemRegistry = field.getGenericType().getTypeName().contains(itemType.getName());
        if (!typedItemRegistry) {
          Object stone = lookup(candidate, stoneLocation, itemType);
          if (!itemType.isInstance(stone)) continue;
        }
        Object registryKey = registryKey(candidate);
        Object itemKey = resourceKey(loader, registryKey, location);
        return new RegistryHandle(candidate, registryKey, location, itemKey, itemType);
      }
    }
    throw new NoSuchFieldException("Minecraft item registry");
  }

  private static Object lookup(Object registry, Object location, Class<?> expectedType) {
    for (Method method : registry.getClass().getMethods()) {
      if (method.getParameterCount() != 1
          || method.getParameterTypes()[0] != location.getClass()) continue;
      if (method.getReturnType() == void.class || method.getReturnType().isPrimitive()) continue;
      try {
        Object value = method.invoke(registry, location);
        if (expectedType.isInstance(value)) return value;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // Try the next erased registry accessor.
      }
    }
    return null;
  }

  private static Object registryKey(Object registry) {
    for (String name : List.of("key", "method_30517", "m_123023_")) {
      try {
        Method method = registry.getClass().getMethod(name);
        if (method.getParameterCount() == 0) return method.invoke(registry);
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // Try the next mapping generation.
      }
    }
    for (Method method : registry.getClass().getMethods()) {
      if (method.getParameterCount() != 0) continue;
      String returnType = method.getReturnType().getName();
      if (!returnType.equals("net.minecraft.resources.ResourceKey")
          && !returnType.equals("net.minecraft.class_5321")) continue;
      try {
        return method.invoke(registry);
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        return null;
      }
    }
    return null;
  }

  private static Object resourceKey(ClassLoader loader, Object registryKey, Object location) {
    if (registryKey == null) return null;
    for (String name : List.of("net.minecraft.resources.ResourceKey", "net.minecraft.class_5321")) {
      try {
        Class<?> type = Class.forName(name, true, loader);
        for (Method method : type.getMethods()) {
          if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 2) continue;
          if (!type.isAssignableFrom(method.getReturnType())) continue;
          if (!method.getParameterTypes()[0].isInstance(registryKey)
              || !method.getParameterTypes()[1].isInstance(location)) continue;
          return method.invoke(null, registryKey, location);
        }
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // Legacy item properties do not require a resource key.
      }
    }
    return null;
  }

  private static Object createItem(RegistryHandle handle) throws ReflectiveOperationException {
    Class<?> propertiesType = Arrays.stream(handle.itemType().getDeclaredClasses())
        .filter(type -> type.getSimpleName().equals("Properties")
            || type.getName().endsWith("$class_1793"))
        .filter(type -> Arrays.stream(type.getConstructors())
            .anyMatch(constructor -> constructor.getParameterCount() == 0))
        .findFirst().orElseThrow(() -> new NoSuchMethodException("Item.Properties"));
    Object properties = propertiesType.getConstructor().newInstance();
    if (handle.itemKey() != null) {
      for (Method method : propertiesType.getMethods()) {
        if (method.getParameterCount() != 1
            || !method.getParameterTypes()[0].isInstance(handle.itemKey())) continue;
        if (!propertiesType.isAssignableFrom(method.getReturnType())) continue;
        if (!method.getName().equals("setId") && !method.getName().equals("method_63689")) continue;
        try {
          properties = method.invoke(properties, handle.itemKey());
          break;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
          // Only modern versions require this ID-bearing properties method.
        }
      }
    }
    for (Constructor<?> constructor : handle.itemType().getConstructors()) {
      if (constructor.getParameterCount() == 1
          && constructor.getParameterTypes()[0].isInstance(properties)) {
        return constructor.newInstance(properties);
      }
    }
    throw new NoSuchMethodException("Item(Item.Properties)");
  }

  private static Method staticRegister(RegistryHandle handle, Object item)
      throws ReflectiveOperationException {
    for (Class<?> type : List.of(loadFirst(FixtureItemRegistration.class.getClassLoader(),
        "net.minecraft.core.Registry", "net.minecraft.class_2378"), handle.registry().getClass())) {
      for (Method method : type.getMethods()) {
        if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 3) continue;
        if (!method.getName().equals("register") && !method.getName().equals("method_10230")
            && !method.getName().equals("m_122965_")
            && !method.getName().equals("m_194579_")) continue;
        if (!method.getParameterTypes()[0].isInstance(handle.registry())) continue;
        if (!method.getParameterTypes()[1].isInstance(handle.location())
            || !method.getParameterTypes()[2].isInstance(item)) continue;
        return method;
      }
    }
    throw new NoSuchMethodException("Registry.register");
  }

  private static Object location(ClassLoader loader, String namespace, String path)
      throws ReflectiveOperationException {
    Class<?> type = loadFirst(loader, "net.minecraft.resources.ResourceLocation",
        "net.minecraft.resources.Identifier", "net.minecraft.class_2960");
    for (Method method : type.getMethods()) {
      if (!Modifier.isStatic(method.getModifiers()) || !type.isAssignableFrom(method.getReturnType())) continue;
      if (method.getParameterCount() == 2 && method.getParameterTypes()[0] == String.class
          && method.getParameterTypes()[1] == String.class) {
        try {
          Object value = method.invoke(null, namespace, path);
          if (value != null) return value;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
          // Try another factory or constructor.
        }
      }
    }
    for (Constructor<?> constructor : type.getConstructors()) {
      if (constructor.getParameterCount() == 2
          && constructor.getParameterTypes()[0] == String.class
          && constructor.getParameterTypes()[1] == String.class) {
        return constructor.newInstance(namespace, path);
      }
    }
    for (Method method : type.getMethods()) {
      if (!Modifier.isStatic(method.getModifiers()) || !type.isAssignableFrom(method.getReturnType())) continue;
      if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == String.class) {
        Object value = method.invoke(null, namespace + ":" + path);
        if (value != null) return value;
      }
    }
    throw new NoSuchMethodException("resource identifier factory");
  }

  private static void verify(RegistryHandle handle, Object expectedItem) {
    Object registered = lookup(handle.registry(), handle.location(), handle.itemType());
    if (registered != expectedItem) {
      throw new IllegalStateException("fixture registry lookup failed");
    }
    System.out.println("[WebShopX Fixture] registered registry=" + MOD_ID + ":" + ITEM_NAME);
  }

  private static Class<?> loadFirst(ClassLoader loader, String... names)
      throws ClassNotFoundException {
    for (String name : names) {
      try {
        return Class.forName(name, true, loader);
      } catch (ClassNotFoundException ignored) {
        // Try the other mapping generation.
      }
    }
    throw new ClassNotFoundException(String.join(",", names));
  }

  private static Throwable root(Throwable failure) {
    Throwable current = Objects.requireNonNull(failure);
    while (current instanceof java.lang.reflect.InvocationTargetException invocation
        && invocation.getCause() != null) current = invocation.getCause();
    return current;
  }

  private record RegistryHandle(
      Object registry, Object registryKey, Object location, Object itemKey, Class<?> itemType) { }
}
