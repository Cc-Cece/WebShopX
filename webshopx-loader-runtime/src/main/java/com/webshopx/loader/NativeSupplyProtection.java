package com.webshopx.loader;

import com.webshopx.SharedCommerceService;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/** Native-event access checks for Loader supply containers. */
final class NativeSupplyProtection {
  enum Action {
    INTERACT,
    BREAK,
    AUTOMATION,
    EXPLOSION
  }

  private NativeSupplyProtection() {}

  static boolean denied(
      SharedCommerceService commerce, Object player, Object level, Object position, Action action) {
    try {
      SharedCommerceService.SupplyProtection protection = commerce.supplyProtectionAt(
          worldName(level), coordinate(position, "x"), coordinate(position, "y"),
          coordinate(position, "z"));
      if (protection == null) return false;
      if (action != Action.INTERACT) return true;
      if (!protection.accessProtected()) return false;
      UUID actor = player == null ? null : playerId(player);
      return !protection.ownerId().equals(actor);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
      System.err.printf(
          "[WebShopX] supply protection check failed action=%s detail=%s: %s%n",
          action, failure.getClass().getSimpleName(), failure.getMessage());
      return true;
    }
  }

  static Object enumResult(Class<?> resultType, String name) {
    if (resultType == boolean.class || resultType == Boolean.class) {
      return !"FAIL".equals(name);
    }
    if (!resultType.isEnum()) return null;
    for (Object constant : resultType.getEnumConstants()) {
      if (((Enum<?>) constant).name().equalsIgnoreCase(name)) return constant;
    }
    throw new IllegalStateException(
        "native callback result is missing " + name + ": " + resultType.getName());
  }

  private static UUID playerId(Object player) throws ReflectiveOperationException {
    Object value = invoke(player, new String[] {"getUUID", "getUuid", "method_5667", "m_20148_"});
    if (value instanceof UUID uuid) return uuid;
    throw new IllegalStateException("native player UUID is unavailable");
  }

  private static int coordinate(Object position, String axis) throws ReflectiveOperationException {
    String[] names = switch (axis) {
      case "x" -> new String[] {"getX", "method_10263", "m_123341_"};
      case "y" -> new String[] {"getY", "method_10264", "m_123342_"};
      case "z" -> new String[] {"getZ", "method_10260", "m_123343_"};
      default -> throw new IllegalArgumentException("unknown axis " + axis);
    };
    return ((Number) invoke(position, names)).intValue();
  }

  private static String worldName(Object level) throws ReflectiveOperationException {
    Object dimension = invoke(level, new String[] {"dimension", "getRegistryKey", "method_27983"});
    Object location;
    try {
      location = invoke(dimension, new String[] {"location", "getValue", "method_29177"});
    } catch (ReflectiveOperationException unavailable) {
      location = dimension;
    }
    return location.toString().toLowerCase(Locale.ROOT);
  }

  private static Object invoke(Object target, String[] names) throws ReflectiveOperationException {
    Method method = Arrays.stream(target.getClass().getMethods())
        .filter(candidate -> candidate.getParameterCount() == 0)
        .filter(candidate -> Arrays.asList(names).contains(candidate.getName()))
        .findFirst()
        .orElseThrow(() -> new NoSuchMethodException(
            target.getClass().getName() + "." + String.join("/", names)));
    return method.invoke(target);
  }
}
