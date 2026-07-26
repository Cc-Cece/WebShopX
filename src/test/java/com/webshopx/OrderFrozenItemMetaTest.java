package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class OrderFrozenItemMetaTest {
  @Test
  void readsOnlyMetadataFrozenIntoDeliveryPayload() throws Exception {
    Method method =
        OrderService.class.getDeclaredMethod("readFrozenItemMetaJson", String.class);
    method.setAccessible(true);

    String meta =
        "{\"material\":\"BUNDLE\",\"containerItems\":[{\"slot\":0,\"item\":{\"material\":\"STONE\"}}]}";
    String payload =
        "{\"itemHash\":\"hash\",\"itemMetaJsonBase64\":\""
            + Base64.getEncoder().encodeToString(meta.getBytes(StandardCharsets.UTF_8))
            + "\"}";

    assertEquals(meta, method.invoke(null, payload));
    assertNull(method.invoke(null, "{\"itemHash\":\"legacy\"}"));
    assertNull(method.invoke(null, "{malformed"));
  }
}
