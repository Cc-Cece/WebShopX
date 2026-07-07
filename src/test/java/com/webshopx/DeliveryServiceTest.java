package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class DeliveryServiceTest {
  @Test
  void coerceNullableLongAcceptsDriverNumericVariants() throws Exception {
    assertNull(DeliveryService.coerceNullableLong(null, "trade_id"));
    assertNull(DeliveryService.coerceNullableLong(" ", "trade_id"));
    assertEquals(12L, DeliveryService.coerceNullableLong(Integer.valueOf(12), "trade_id"));
    assertEquals(34L, DeliveryService.coerceNullableLong(Long.valueOf(34), "trade_id"));
    assertEquals(56L, DeliveryService.coerceNullableLong("56", "trade_id"));
  }

  @Test
  void coerceNullableLongRejectsUnexpectedValues() {
    assertThrows(SQLException.class, () -> DeliveryService.coerceNullableLong("bad", "trade_id"));
    assertThrows(SQLException.class, () -> DeliveryService.coerceNullableLong(new Object(), "trade_id"));
  }
}
