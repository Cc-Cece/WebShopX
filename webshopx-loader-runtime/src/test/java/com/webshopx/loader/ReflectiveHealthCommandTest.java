package com.webshopx.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class ReflectiveHealthCommandTest {
  @Test
  void commandProxiesImplementObjectMethodsRequiredByBrigadierHashing() {
    Runnable proxy = (Runnable) Proxy.newProxyInstance(
        Runnable.class.getClassLoader(), new Class<?>[]{Runnable.class},
        (instance, method, arguments) -> method.getDeclaringClass() == Object.class
            ? ReflectiveHealthCommand.proxyObjectMethod(instance, method, arguments)
            : null);
    Runnable other = () -> { };

    assertEquals(System.identityHashCode(proxy), proxy.hashCode());
    assertTrue(proxy.equals(proxy));
    assertNotEquals(proxy, other);
    assertTrue(proxy.toString().startsWith("WebShopXProxy["));
  }
}
