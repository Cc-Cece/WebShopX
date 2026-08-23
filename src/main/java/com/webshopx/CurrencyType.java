package com.webshopx;

/**
 * Supported currency types for wallet and pricing.
 */
public enum CurrencyType {
  SHOP_COIN,
  GAME_COIN;

  public static CurrencyType fromConfig(String raw) {
    if (raw == null) {
      return SHOP_COIN;
    }
    String normalized = raw.trim().toUpperCase();
    if (normalized.equals("GAME_COIN") || normalized.equals("GAMECOIN")) {
      return GAME_COIN;
    }
    return SHOP_COIN;
  }

  String columnName() {
    return switch (this) {
      case SHOP_COIN -> "shop_coin";
      case GAME_COIN -> "game_coin";
    };
  }
}
