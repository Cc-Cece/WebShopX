package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MarketGuiServiceTest {
  @Test
  void parsePriceInputAcceptsVisibleDigitsFromHybridChat() {
    assertEquals(1L, MarketGuiService.parsePriceInput("1"));
    assertEquals(123L, MarketGuiService.parsePriceInput("\u200b１２３"));
    assertEquals(45L, MarketGuiService.parsePriceInput("\u00a7a45"));
  }

  @Test
  void parsePriceInputStillRequiresPureInteger() {
    assertThrows(NumberFormatException.class, () -> MarketGuiService.parsePriceInput(""));
    assertThrows(NumberFormatException.class, () -> MarketGuiService.parsePriceInput("1 2"));
    assertThrows(NumberFormatException.class, () -> MarketGuiService.parsePriceInput("12 coins"));
  }
}
