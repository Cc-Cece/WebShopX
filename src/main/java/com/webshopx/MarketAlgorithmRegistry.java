package com.webshopx;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

final class MarketAlgorithmRegistry {
  private static final long MAX_SAFE_PRICE = Long.MAX_VALUE;
  private static final long DEFAULT_THRESHOLD_K = 4L;
  private static final long DEFAULT_PANIC_THRESHOLD = 20L;

  private static final Map<DynamicAlgorithmType, DynamicPricingStrategy> DYNAMIC_STRATEGIES =
      initDynamicStrategies();

  private MarketAlgorithmRegistry() {
  }

  enum DynamicAlgorithmType {
    LINEAR_DEMAND_V1,
    DIMINISHING_RETURN_V1,
    LOG_SMOOTH_V1,
    EXPONENTIAL_DEFENSE_V1,
    THRESHOLD_STEP_V1,
    ELASTICITY_V1,
    PANIC_BUYING_V1;

    static DynamicAlgorithmType fromRaw(String raw) {
      if (raw == null || raw.isBlank()) {
        return LINEAR_DEMAND_V1;
      }
      String normalized = raw.trim().toUpperCase(Locale.ROOT);
      try {
        return DynamicAlgorithmType.valueOf(normalized);
      } catch (IllegalArgumentException exception) {
        return LINEAR_DEMAND_V1;
      }
    }
  }

  enum AuctionAlgorithmType {
    ENGLISH_AUCTION_V1,
    DUTCH_AUCTION_V1,
    VICKREY_AUCTION_V1,
    CANDLE_AUCTION_V1;

    static AuctionAlgorithmType fromRaw(String raw) {
      if (raw == null || raw.isBlank()) {
        return ENGLISH_AUCTION_V1;
      }
      String normalized = raw.trim().toUpperCase(Locale.ROOT);
      try {
        return AuctionAlgorithmType.valueOf(normalized);
      } catch (IllegalArgumentException exception) {
        return ENGLISH_AUCTION_V1;
      }
    }
  }

  interface DynamicPricingStrategy {
    long demandDelta(long currentDemand, int buyQuantity, JsonObject params);

    long computeRawPrice(long basePrice, long demandHeat, long step, JsonObject params);
  }

  static boolean supportsBid(AuctionAlgorithmType algorithm) {
    return algorithm != AuctionAlgorithmType.DUTCH_AUCTION_V1;
  }

  static boolean supportsDirectBuy(AuctionAlgorithmType algorithm) {
    return algorithm == AuctionAlgorithmType.DUTCH_AUCTION_V1;
  }

  static boolean sealedBid(AuctionAlgorithmType algorithm) {
    return algorithm == AuctionAlgorithmType.VICKREY_AUCTION_V1;
  }

  static long computeDemandAfterPurchase(
      DynamicAlgorithmType algorithm,
      long currentDemand,
      int buyQuantity,
      JsonObject params) {
    DynamicPricingStrategy strategy = DYNAMIC_STRATEGIES.getOrDefault(
        algorithm,
        DYNAMIC_STRATEGIES.get(DynamicAlgorithmType.LINEAR_DEMAND_V1));
    long delta = Math.max(0L, strategy.demandDelta(Math.max(0L, currentDemand), Math.max(1, buyQuantity), params));
    return safeAdd(Math.max(0L, currentDemand), delta);
  }

  static long computeDemandAfterRecycle(
      DynamicAlgorithmType algorithm,
      long currentDemand,
      int recycleQuantity,
      JsonObject params) {
    DynamicPricingStrategy strategy = DYNAMIC_STRATEGIES.getOrDefault(
        algorithm,
        DYNAMIC_STRATEGIES.get(DynamicAlgorithmType.LINEAR_DEMAND_V1));
    long delta = Math.max(0L, strategy.demandDelta(Math.max(0L, currentDemand), Math.max(1, recycleQuantity), params));
    return Math.max(0L, Math.max(0L, currentDemand) - delta);
  }

  static long computeDemandAfterDecay(long currentDemand, int decayStep) {
    return Math.max(0L, currentDemand - Math.max(1, decayStep));
  }

  static long computeDynamicPrice(
      DynamicAlgorithmType algorithm,
      long basePrice,
      long demandHeat,
      long step,
      Long floorPrice,
      Long capPrice,
      JsonObject params) {
    DynamicPricingStrategy strategy = DYNAMIC_STRATEGIES.getOrDefault(
        algorithm,
        DYNAMIC_STRATEGIES.get(DynamicAlgorithmType.LINEAR_DEMAND_V1));
    long rawPrice = strategy.computeRawPrice(
        Math.max(1L, basePrice),
        Math.max(0L, demandHeat),
        Math.max(1L, step),
        params);
    return applyBounds(rawPrice, floorPrice, capPrice);
  }

  static long computeDutchPrice(
      long startPrice,
      long floorPrice,
      LocalDateTime startedAt,
      LocalDateTime endAt,
      LocalDateTime now) {
    long normalizedStart = Math.max(1L, startPrice);
    long normalizedFloor = Math.max(1L, Math.min(floorPrice, normalizedStart));
    if (startedAt == null || endAt == null || !endAt.isAfter(startedAt)) {
      return normalizedStart;
    }
    long totalSeconds = Math.max(1L, ChronoUnit.SECONDS.between(startedAt, endAt));
    long elapsedSeconds = ChronoUnit.SECONDS.between(startedAt, now == null ? LocalDateTime.now() : now);
    long clampedElapsed = Math.max(0L, Math.min(totalSeconds, elapsedSeconds));

    double ratio = (double) clampedElapsed / (double) totalSeconds;
    double delta = (double) (normalizedStart - normalizedFloor) * ratio;
    long price = (long) Math.floor(normalizedStart - delta);
    return Math.max(normalizedFloor, Math.max(1L, price));
  }

  static LocalDateTime computeCandleActualEnd(LocalDateTime publicEndAt, int maxExtensionSeconds) {
    int extension = 0;
    if (maxExtensionSeconds > 0) {
      extension = ThreadLocalRandom.current().nextInt(maxExtensionSeconds + 1);
    }
    return publicEndAt.plusSeconds(extension);
  }

  static JsonObject parseParams(String rawJson) {
    if (rawJson == null || rawJson.isBlank()) {
      return new JsonObject();
    }
    try {
      JsonElement parsed = JsonParser.parseString(rawJson);
      if (parsed.isJsonObject()) {
        return parsed.getAsJsonObject();
      }
      return new JsonObject();
    } catch (Exception ignored) {
      return new JsonObject();
    }
  }

  static String toJson(JsonObject object) {
    if (object == null || object.size() == 0) {
      return null;
    }
    return object.toString();
  }

  static long getLongParam(JsonObject params, String key, long defaultValue) {
    if (params == null || !params.has(key) || params.get(key).isJsonNull()) {
      return defaultValue;
    }
    try {
      return params.get(key).getAsLong();
    } catch (Exception exception) {
      return defaultValue;
    }
  }

  static double getDoubleParam(JsonObject params, String key, double defaultValue) {
    if (params == null || !params.has(key) || params.get(key).isJsonNull()) {
      return defaultValue;
    }
    try {
      return params.get(key).getAsDouble();
    } catch (Exception exception) {
      return defaultValue;
    }
  }

  private static long applyBounds(long rawPrice, Long floorPrice, Long capPrice) {
    long bounded = Math.max(1L, rawPrice);
    if (floorPrice != null) {
      bounded = Math.max(bounded, Math.max(1L, floorPrice));
    }
    if (capPrice != null) {
      bounded = Math.min(bounded, Math.max(1L, capPrice));
    }
    return Math.max(1L, bounded);
  }

  private static long safeAdd(long left, long right) {
    if (right > 0 && left > Long.MAX_VALUE - right) {
      return Long.MAX_VALUE;
    }
    if (right < 0 && left < Long.MIN_VALUE - right) {
      return Long.MIN_VALUE;
    }
    return left + right;
  }

  private static long safeMultiply(long left, long right) {
    if (left <= 0 || right <= 0) {
      return 0L;
    }
    if (left > Long.MAX_VALUE / right) {
      return Long.MAX_VALUE;
    }
    return left * right;
  }

  private static Map<DynamicAlgorithmType, DynamicPricingStrategy> initDynamicStrategies() {
    Map<DynamicAlgorithmType, DynamicPricingStrategy> strategies = new EnumMap<>(DynamicAlgorithmType.class);

    strategies.put(
        DynamicAlgorithmType.LINEAR_DEMAND_V1,
        new DynamicPricingStrategy() {
          @Override
          public long demandDelta(long currentDemand, int buyQuantity, JsonObject params) {
            return Math.max(1, buyQuantity);
          }

          @Override
          public long computeRawPrice(long basePrice, long demandHeat, long step, JsonObject params) {
            return safeAdd(basePrice, safeMultiply(demandHeat, step));
          }
        });

    strategies.put(
        DynamicAlgorithmType.DIMINISHING_RETURN_V1,
        new DynamicPricingStrategy() {
          @Override
          public long demandDelta(long currentDemand, int buyQuantity, JsonObject params) {
            return Math.max(1L, Math.round(Math.sqrt(Math.max(1, buyQuantity))));
          }

          @Override
          public long computeRawPrice(long basePrice, long demandHeat, long step, JsonObject params) {
            return safeAdd(basePrice, safeMultiply(demandHeat, step));
          }
        });

    strategies.put(
        DynamicAlgorithmType.LOG_SMOOTH_V1,
        new DynamicPricingStrategy() {
          @Override
          public long demandDelta(long currentDemand, int buyQuantity, JsonObject params) {
            double delta = Math.log(Math.max(1, buyQuantity) + 1D);
            return Math.max(1L, Math.round(delta));
          }

          @Override
          public long computeRawPrice(long basePrice, long demandHeat, long step, JsonObject params) {
            return safeAdd(basePrice, safeMultiply(demandHeat, step));
          }
        });

    strategies.put(
        DynamicAlgorithmType.EXPONENTIAL_DEFENSE_V1,
        new DynamicPricingStrategy() {
          @Override
          public long demandDelta(long currentDemand, int buyQuantity, JsonObject params) {
            double delta = Math.pow(Math.max(1, buyQuantity), 1.5D);
            return Math.max(1L, Math.round(delta));
          }

          @Override
          public long computeRawPrice(long basePrice, long demandHeat, long step, JsonObject params) {
            return safeAdd(basePrice, safeMultiply(demandHeat, step));
          }
        });

    strategies.put(
        DynamicAlgorithmType.THRESHOLD_STEP_V1,
        new DynamicPricingStrategy() {
          @Override
          public long demandDelta(long currentDemand, int buyQuantity, JsonObject params) {
            long threshold = Math.max(0L, getLongParam(params, "thresholdK", DEFAULT_THRESHOLD_K));
            return Math.max(0L, Math.max(1, buyQuantity) - threshold);
          }

          @Override
          public long computeRawPrice(long basePrice, long demandHeat, long step, JsonObject params) {
            return safeAdd(basePrice, safeMultiply(demandHeat, step));
          }
        });

    strategies.put(
        DynamicAlgorithmType.ELASTICITY_V1,
        new DynamicPricingStrategy() {
          @Override
          public long demandDelta(long currentDemand, int buyQuantity, JsonObject params) {
            return Math.max(1, buyQuantity);
          }

          @Override
          public long computeRawPrice(long basePrice, long demandHeat, long step, JsonObject params) {
            double epsilon = getDoubleParam(params, "elasticity", 1.0D);
            if (epsilon <= 0D) {
              epsilon = 1.0D;
            }
            double delta = ((double) demandHeat * (double) step) / epsilon;
            long increment = (long) Math.floor(Math.max(0D, delta));
            return safeAdd(basePrice, Math.min(increment, MAX_SAFE_PRICE));
          }
        });

    strategies.put(
        DynamicAlgorithmType.PANIC_BUYING_V1,
        new DynamicPricingStrategy() {
          @Override
          public long demandDelta(long currentDemand, int buyQuantity, JsonObject params) {
            return Math.max(1, buyQuantity);
          }

          @Override
          public long computeRawPrice(long basePrice, long demandHeat, long step, JsonObject params) {
            long threshold = Math.max(0L, getLongParam(params, "panicThreshold", DEFAULT_PANIC_THRESHOLD));
            long linear = safeAdd(basePrice, safeMultiply(demandHeat, step));
            if (demandHeat <= threshold) {
              return linear;
            }
            long extra = safeMultiply(demandHeat - threshold, demandHeat - threshold);
            return safeAdd(linear, extra);
          }
        });

    return strategies;
  }
}
