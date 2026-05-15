package com.webshopx;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

final class YuPayBridge {
  private static final String PLUGIN_NAME = "WebShopX";

  private final JavaPlugin plugin;
  private Object registeredListener;
  private Class<?> registeredApiClass;

  YuPayBridge(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  boolean isAvailable() {
    return resolveApi() != null;
  }

  CreatePaymentResultData createPayment(CreatePaymentRequestData request) {
    ApiHandle handle = requireApi();
    Method method = findMethod(handle.apiClass(), "createPayment", 1);
    Object apiRequest = instantiate(method.getParameterTypes()[0]);
    write(apiRequest, "merchantOrderId", request.merchantOrderId());
    write(apiRequest, "userId", request.userId());
    write(apiRequest, "playerUuid", request.playerUuid());
    write(apiRequest, "amountMinor", request.amountMinor());
    write(apiRequest, "currency", request.currency());
    write(apiRequest, "title", request.title());
    write(apiRequest, "description", request.description());
    write(apiRequest, "returnUrl", request.returnUrl());
    write(apiRequest, "metadata", request.metadata());
    Object result = invoke(method, handle.provider(), apiRequest);
    return new CreatePaymentResultData(
        readBoolean(result, "success"),
        readString(result, "merchantOrderId"),
        readString(result, "providerOrderId"),
        readString(result, "payUrl"),
        readString(result, "qrCodeUrl"),
        readInstant(result, "expireTime"),
        readString(result, "errorCode"),
        readString(result, "message"));
  }

  QueryPaymentResultData queryPayment(String providerOrderId) {
    ApiHandle handle = requireApi();
    Method method = findMethod(handle.apiClass(), "queryPayment", 1);
    Object result = invoke(method, handle.provider(), providerOrderId);
    return new QueryPaymentResultData(
        readBoolean(result, "success"),
        readString(result, "merchantOrderId"),
        readString(result, "providerOrderId"),
        readStatusName(result, "status"),
        readBoolean(result, "paid"),
        readLong(result, "amountMinor"),
        readString(result, "currency"),
        readString(result, "method"),
        readInstant(result, "payTime"),
        readString(result, "errorCode"),
        readString(result, "message"));
  }

  boolean registerPaymentListener(Function<PaymentNotifyData, NotifyResultData> handler) {
    ApiHandle handle = resolveApi();
    if (handle == null) {
      return false;
    }
    Method method = findMethod(handle.apiClass(), "registerPaymentListener", 2);
    Class<?> listenerClass = method.getParameterTypes()[1];
    Object listener = Proxy.newProxyInstance(
        listenerClass.getClassLoader(),
        new Class<?>[] {listenerClass},
        new PaymentListenerInvocationHandler(handler));
    invoke(method, handle.provider(), PLUGIN_NAME, listener);
    registeredApiClass = handle.apiClass();
    registeredListener = listener;
    return true;
  }

  void unregisterPaymentListener() {
    ApiHandle handle = resolveApiByClass(registeredApiClass);
    if (handle == null) {
      registeredListener = null;
      registeredApiClass = null;
      return;
    }
    try {
      Method method = findMethod(handle.apiClass(), "unregisterPaymentListener", 1);
      invoke(method, handle.provider(), PLUGIN_NAME);
    } catch (RuntimeException exception) {
      plugin.getLogger().warning("Failed to unregister YuPay listener: " + exception.getMessage());
    } finally {
      registeredListener = null;
      registeredApiClass = null;
    }
  }

  private ApiHandle requireApi() {
    ApiHandle handle = resolveApi();
    if (handle == null) {
      throw new ServiceException("yupay_unavailable", "YuPay API is not available");
    }
    return handle;
  }

  private ApiHandle resolveApi() {
    ServicesManager services = plugin.getServer().getServicesManager();
    for (Class<?> serviceClass : services.getKnownServices()) {
      if (!"YuPayApi".equals(serviceClass.getSimpleName())) {
        continue;
      }
      ApiHandle handle = resolveApiByClass(serviceClass);
      if (handle != null && hasYuPayMethods(serviceClass)) {
        return handle;
      }
    }
    return null;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private ApiHandle resolveApiByClass(Class<?> apiClass) {
    if (apiClass == null) {
      return null;
    }
    RegisteredServiceProvider provider =
        plugin.getServer().getServicesManager().getRegistration((Class) apiClass);
    if (provider == null || provider.getProvider() == null) {
      return null;
    }
    return new ApiHandle(apiClass, provider.getProvider());
  }

  private boolean hasYuPayMethods(Class<?> apiClass) {
    return findOptionalMethod(apiClass, "createPayment", 1) != null
        && findOptionalMethod(apiClass, "queryPayment", 1) != null
        && findOptionalMethod(apiClass, "registerPaymentListener", 2) != null
        && findOptionalMethod(apiClass, "unregisterPaymentListener", 1) != null;
  }

  private Method findMethod(Class<?> type, String name, int parameterCount) {
    Method method = findOptionalMethod(type, name, parameterCount);
    if (method == null) {
      throw new ServiceException("yupay_api_mismatch", "YuPay API method is missing: " + name);
    }
    method.setAccessible(true);
    return method;
  }

  private Method findOptionalMethod(Class<?> type, String name, int parameterCount) {
    for (Method method : type.getMethods()) {
      if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
        return method;
      }
    }
    for (Method method : type.getDeclaredMethods()) {
      if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
        method.setAccessible(true);
        return method;
      }
    }
    return null;
  }

  private Object instantiate(Class<?> type) {
    try {
      Constructor<?> constructor = type.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (ReflectiveOperationException exception) {
      throw new ServiceException(
          "yupay_api_mismatch",
          "YuPay request type requires a no-args constructor");
    }
  }

  private Object invoke(Method method, Object target, Object... args) {
    try {
      return method.invoke(target, args);
    } catch (ReflectiveOperationException exception) {
      Throwable cause = exception.getCause() == null ? exception : exception.getCause();
      String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
      throw new ServiceException("yupay_api_error", message);
    }
  }

  private void write(Object target, String name, Object value) {
    Method setter = findSetter(target.getClass(), name);
    if (setter != null) {
      invoke(setter, target, value);
      return;
    }
    Field field = findField(target.getClass(), name);
    if (field == null) {
      return;
    }
    try {
      field.setAccessible(true);
      field.set(target, value);
    } catch (IllegalAccessException exception) {
      throw new ServiceException("yupay_api_mismatch", "Cannot write YuPay request field: " + name);
    }
  }

  private Method findSetter(Class<?> type, String fieldName) {
    String expected = "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    for (Method method : type.getMethods()) {
      if (method.getName().equals(expected) && method.getParameterCount() == 1) {
        method.setAccessible(true);
        return method;
      }
    }
    return null;
  }

  private Field findField(Class<?> type, String fieldName) {
    Class<?> current = type;
    while (current != null) {
      try {
        Field field = current.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException ignored) {
        current = current.getSuperclass();
      }
    }
    return null;
  }

  private Object read(Object target, String name) {
    if (target == null) {
      return null;
    }
    Method getter = findGetter(target.getClass(), name);
    if (getter != null) {
      return invoke(getter, target);
    }
    Field field = findField(target.getClass(), name);
    if (field == null) {
      return null;
    }
    try {
      return field.get(target);
    } catch (IllegalAccessException exception) {
      throw new ServiceException("yupay_api_mismatch", "Cannot read YuPay result field: " + name);
    }
  }

  private Method findGetter(Class<?> type, String fieldName) {
    String suffix = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    for (String methodName : new String[] {"get" + suffix, "is" + suffix}) {
      for (Method method : type.getMethods()) {
        if (method.getName().equals(methodName) && method.getParameterCount() == 0) {
          method.setAccessible(true);
          return method;
        }
      }
    }
    return null;
  }

  private boolean readBoolean(Object target, String name) {
    Object value = read(target, name);
    if (value instanceof Boolean bool) {
      return bool;
    }
    return value != null && Boolean.parseBoolean(String.valueOf(value));
  }

  private long readLong(Object target, String name) {
    Object value = read(target, name);
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return 0L;
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException exception) {
      return 0L;
    }
  }

  private String readString(Object target, String name) {
    Object value = read(target, name);
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value);
    return text.isBlank() ? null : text;
  }

  private String readStatusName(Object target, String name) {
    Object value = read(target, name);
    if (value == null) {
      return null;
    }
    if (value instanceof Enum<?> enumValue) {
      return enumValue.name();
    }
    return String.valueOf(value).trim().toUpperCase(Locale.ROOT);
  }

  private Instant readInstant(Object target, String name) {
    Object value = read(target, name);
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof java.util.Date date) {
      return date.toInstant();
    }
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(String.valueOf(value));
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private Object notifyResultObject(Class<?> returnType, NotifyResultData result) {
    Method factory = findStaticFactory(
        returnType,
        result.success() ? "ok" : "fail",
        result.success() ? 1 : 2);
    if (factory != null) {
      return result.success()
          ? invoke(factory, null, result.message())
          : invoke(factory, null, result.code(), result.message());
    }
    try {
      Constructor<?> constructor = returnType.getDeclaredConstructor(
          boolean.class,
          String.class,
          String.class);
      constructor.setAccessible(true);
      return constructor.newInstance(result.success(), result.code(), result.message());
    } catch (ReflectiveOperationException ignored) {
      Object target = instantiate(returnType);
      write(target, "success", result.success());
      write(target, "code", result.code());
      write(target, "message", result.message());
      return target;
    }
  }

  private Method findStaticFactory(Class<?> type, String name, int parameterCount) {
    for (Method method : type.getMethods()) {
      if (method.getName().equals(name)
          && method.getParameterCount() == parameterCount
          && java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
        method.setAccessible(true);
        return method;
      }
    }
    return null;
  }

  private PaymentNotifyData readNotify(Object notify) {
    return new PaymentNotifyData(
        readString(notify, "merchantOrderId"),
        readString(notify, "providerOrderId"),
        readStatusName(notify, "status"),
        readLong(notify, "amountMinor"),
        readString(notify, "currency"),
        readString(notify, "method"),
        readInstant(notify, "payTime"),
        readStringMap(notify, "extra"));
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> readStringMap(Object target, String name) {
    Object value = read(target, name);
    if (value instanceof Map<?, ?> map) {
      Map<String, String> normalized = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() != null && entry.getValue() != null) {
          normalized.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
      }
      return normalized;
    }
    if (value instanceof Collection<?>) {
      return Map.of();
    }
    return Map.of();
  }

  private final class PaymentListenerInvocationHandler implements InvocationHandler {
    private final Function<PaymentNotifyData, NotifyResultData> handler;

    private PaymentListenerInvocationHandler(
        Function<PaymentNotifyData, NotifyResultData> handler) {
      this.handler = handler;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      if (method.getDeclaringClass() == Object.class) {
        return switch (method.getName()) {
          case "toString" -> "WebShopX YuPay payment listener";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          default -> null;
        };
      }
      PaymentNotifyData notify = args == null || args.length == 0 ? null : readNotify(args[0]);
      NotifyResultData result = handler.apply(notify);
      return notifyResultObject(method.getReturnType(), result);
    }
  }

  record CreatePaymentRequestData(
      String merchantOrderId,
      String userId,
      UUID playerUuid,
      long amountMinor,
      String currency,
      String title,
      String description,
      String returnUrl,
      Map<String, String> metadata) {
  }

  record CreatePaymentResultData(
      boolean success,
      String merchantOrderId,
      String providerOrderId,
      String payUrl,
      String qrCodeUrl,
      Instant expireTime,
      String errorCode,
      String message) {
  }

  record QueryPaymentResultData(
      boolean success,
      String merchantOrderId,
      String providerOrderId,
      String status,
      boolean paid,
      long amountMinor,
      String currency,
      String method,
      Instant payTime,
      String errorCode,
      String message) {
  }

  record PaymentNotifyData(
      String merchantOrderId,
      String providerOrderId,
      String status,
      long amountMinor,
      String currency,
      String method,
      Instant payTime,
      Map<String, String> extra) {
  }

  record NotifyResultData(boolean success, String code, String message) {
    static NotifyResultData ok(String message) {
      return new NotifyResultData(true, "OK", message);
    }

    static NotifyResultData fail(String code, String message) {
      return new NotifyResultData(false, code, message);
    }
  }

  private record ApiHandle(Class<?> apiClass, Object provider) {
  }
}
