package com.webshopx;

import java.util.Locale;

enum MarketSide {
  SELL,
  BUY;

  static MarketSide fromRaw(String raw) {
    if (raw == null || raw.isBlank()) {
      return SELL;
    }
    try {
      return MarketSide.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new ServiceException("invalid_market_side", "Market side must be SELL or BUY");
    }
  }
}

