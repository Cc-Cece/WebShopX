package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class MarketAlgorithmRegistryTest {
  @Test
  void marginalPurchaseShouldRaisePriceAndRecycleShouldLowerIt() {
    JsonObject params = new JsonObject();
    MarketAlgorithmRegistry.DynamicPriceQuote purchase = MarketAlgorithmRegistry.computeDynamicPriceQuote(
        MarketAlgorithmRegistry.DynamicAlgorithmType.LINEAR_DEMAND_V1,
        MarketAlgorithmRegistry.DynamicPricingMode.PER_UNIT_MARGINAL,
        MarketAlgorithmRegistry.DynamicPriceDirection.PURCHASE,
        100L, 0L, 3, 10L, 1L, 1_000L, params);
    MarketAlgorithmRegistry.DynamicPriceQuote recycle = MarketAlgorithmRegistry.computeDynamicPriceQuote(
        MarketAlgorithmRegistry.DynamicAlgorithmType.LINEAR_DEMAND_V1,
        MarketAlgorithmRegistry.DynamicPricingMode.PER_UNIT_MARGINAL,
        MarketAlgorithmRegistry.DynamicPriceDirection.RECYCLE,
        100L, 0L, 3, 10L, 1L, 1_000L, params);

    assertTrue(purchase.lastUnitPrice() > purchase.firstUnitPrice());
    assertTrue(recycle.lastUnitPrice() < recycle.firstUnitPrice());
    assertEquals(3L, purchase.nextDemandScore());
    assertEquals(-3L, recycle.nextDemandScore());
    assertEquals(70L, recycle.nextUnitPrice());
  }

  @Test
  void decayShouldMoveSignedPressureTowardEquilibrium() {
    assertEquals(3L, MarketAlgorithmRegistry.computeDemandAfterDecay(5L, 2));
    assertEquals(-3L, MarketAlgorithmRegistry.computeDemandAfterDecay(-5L, 2));
    assertEquals(0L, MarketAlgorithmRegistry.computeDemandAfterDecay(-1L, 2));
  }
}
