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
  private static final double DEFAULT_DIMINISHING_B = 0.1D;
  private static final double DEFAULT_LOG_ALPHA = 0.05D;
  private static final double DEFAULT_EXPONENTIAL_BETA = 0.01D;
  private static final long DEFAULT_THRESHOLD_DEMAND = 20L;
  private static final double DEFAULT_THRESHOLD_K2_MULTIPLIER = 3.0D;
  private static final double DEFAULT_ELASTICITY_EPSILON = 1.0D;
  private static final double DEFAULT_ELASTICITY_D0 = 1.0D;
  private static final double DEFAULT_ELASTICITY_ETA = 1.0D;
  private static final double DEFAULT_PANIC_GAMMA = 1.0D;
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

  enum DynamicPricingMode {
    ORDER_FIXED,
    PER_UNIT_MARGINAL;

    static DynamicPricingMode fromRaw(String raw) {
      if (raw == null || raw.isBlank()) {
        return ORDER_FIXED;
      }
      String normalized = raw.trim().toUpperCase(Locale.ROOT);
      try {
        return DynamicPricingMode.valueOf(normalized);
      } catch (IllegalArgumentException exception) {
        return ORDER_FIXED;
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

  static DynamicPriceQuote computeDynamicPriceQuote(
      DynamicAlgorithmType algorithm,
      DynamicPricingMode pricingMode,
      long basePrice,
      long currentDemand,
      int quantity,
      long step,
      Long floorPrice,
      Long capPrice,
      JsonObject params) {
    int normalizedQuantity = Math.max(1, quantity);
    long demand = Math.max(0L, currentDemand);
    long firstUnitPrice = computeDynamicPrice(algorithm, basePrice, demand, step, floorPrice, capPrice, params);
    long totalAmount;
    long lastUnitPrice;
    long nextDemand;
    if (pricingMode == DynamicPricingMode.PER_UNIT_MARGINAL) {
      totalAmount = 0L;
      lastUnitPrice = firstUnitPrice;
      for (int index = 0; index < normalizedQuantity; index++) {
        long unitPrice = computeDynamicPrice(algorithm, basePrice, demand, step, floorPrice, capPrice, params);
        totalAmount = safeAdd(totalAmount, unitPrice);
        lastUnitPrice = unitPrice;
        demand = computeDemandAfterPurchase(algorithm, demand, 1, params);
      }
      nextDemand = demand;
    } else {
      totalAmount = safeMultiply(firstUnitPrice, normalizedQuantity);
      lastUnitPrice = firstUnitPrice;
      nextDemand = computeDemandAfterPurchase(algorithm, demand, normalizedQuantity, params);
    }
    long averageUnitPrice = Math.max(1L, totalAmount / normalizedQuantity);
    long nextUnitPrice = computeDynamicPrice(algorithm, basePrice, nextDemand, step, floorPrice, capPrice, params);
    return new DynamicPriceQuote(
        pricingMode,
        firstUnitPrice,
        lastUnitPrice,
        averageUnitPrice,
        totalAmount,
        nextDemand,
        nextUnitPrice);
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

  private static long getLongParamWithAliases(JsonObject params, long defaultValue, String... keys) {
    if (params == null || keys == null) {
      return defaultValue;
    }
    for (String key : keys) {
      if (key == null || key.isBlank() || !params.has(key) || params.get(key).isJsonNull()) {
        continue;
      }
      try {
        return params.get(key).getAsLong();
      } catch (Exception ignored) {
        continue;
      }
    }
    return defaultValue;
  }

  private static double getDoubleParamWithAliases(JsonObject params, double defaultValue, String... keys) {
    if (params == null || keys == null) {
      return defaultValue;
    }
    for (String key : keys) {
      if (key == null || key.isBlank() || !params.has(key) || params.get(key).isJsonNull()) {
        continue;
      }
      try {
        return params.get(key).getAsDouble();
      } catch (Exception ignored) {
        continue;
      }
    }
    return defaultValue;
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
    if (left == 0L || right == 0L) {
      return 0L;
    }
    if (left > Long.MAX_VALUE / right) {
      return Long.MAX_VALUE;
    }
    return left * right;
  }

  record DynamicPriceQuote(
      DynamicPricingMode pricingMode,
      long firstUnitPrice,
      long lastUnitPrice,
      long averageUnitPrice,
      long totalAmount,
      long nextDemandScore,
      long nextUnitPrice) {
  }

  private static long toLongPrice(double value) {
    if (!Double.isFinite(value) || value <= 0D) {
      return 1L;
    }
    if (value >= (double) MAX_SAFE_PRICE) {
      return MAX_SAFE_PRICE;
    }
    return Math.max(1L, (long) Math.floor(value));
  }

  private static Map<DynamicAlgorithmType, DynamicPricingStrategy> initDynamicStrategies() {
    Map<DynamicAlgorithmType, DynamicPricingStrategy> strategies = new EnumMap<>(DynamicAlgorithmType.class);

    strategies.put(
        DynamicAlgorithmType.LINEAR_DEMAND_V1,
        new DynamicPricingStrategy() {
          @Override
          public long demandDelta(long currentDemand, int buyQuantity, JsonObject params) {
            return Math.max(1L, buyQuantity);
          }

          @Override
          public long computeRawPrice(long basePrice, long demandHeat, long step, JsonObject params) {
            double k = Math.max(0D, getDoubleParamWithAliases(params, (double) step, "k"));
            double rawPrice = (double) basePrice + (k * (double) demandHeat);
            return toLongPrice(rawPrice);
          }
        });

    strategies.put(
        DynamicAlgorithmType.DIMINISHING_RETURN_V1,
        new DynamicPricingStrategy() {
          @Override
          public long demandDelta(long currentDemand, int buyQuantity, JsonObject params) {
            return Math.max(1L, buyQuantity);
          }

          @Override
          public long computeRawPrice(long basePrice, long demandHeat, long step, JsonObject params) {
            double a = Math.max(0D, getDoubleParamWithAliases(params, (double) step, "a"));
            double b = Math.max(0D, getDoubleParamWithAliases(params, DEFAULT_DIMINISHING_B, "b"));
            double demand = (double) demandHeat;
            double rawPrice = (double) basePrice;
            if (demand > 0D) {
              rawPrice += a * (demand / (1D + (b * demand)));
            }
            return toLongPrice(rawPrice);
          }
        });

    strategies.put(
        DynamicAlgorithmType.LOG_SMOOTH_V1,
        new DynamicPricingStrategy() {
          @Override
          public long demandDelta(long currentDemand, int buyQuantity, JsonObject params) {
            return Math.max(1L, buyQuantity);
          }

          @Override
          public long computeRawPrice(long basePrice, long demandHeat, long step, JsonObject params) {
            double alpha = Math.max(0D, getDoubleParamWithAliases(params, DEFAULT_LOG_ALPHA, "alpha"));
            double rawPrice = (double) basePrice * (1D + (alpha * Math.log1p((double) demandHeat)));
            return toLongPrice(rawPrice);
          }
        });

    strategies.put(
        DynamicAlgorithmType.EXPONENTIAL_DEFENSE_V1,
        new DynamicPricingStrategy() {
          @Override
          public long demandDelta(long currentDemand, int buyQuantity, JsonObject params) {
            return Math.max(1L, buyQuantity);
          }

          @Override
          public long computeRawPrice(long basePrice, long demandHeat, long step, JsonObject params) {
            double beta = Math.max(0D, getDoubleParamWithAliases(params, DEFAULT_EXPONENTIAL_BETA, "beta"));
            double exponent = Math.min(40D, beta * (double) demandHeat);
            double rawPrice = (double) basePrice * Math.exp(exponent);
            return toLongPrice(rawPrice);
          }
        });

    strategies.put(
        DynamicAlgorithmType.THRESHOLD_STEP_V1,
        new DynamicPricingStrategy() {
          @Override
          public long demandDelta(long currentDemand, int buyQuantity, JsonObject params) {
            return Math.max(1L, buyQuantity);
          }

          @Override
          public long computeRawPrice(long basePrice, long demandHeat, long step, JsonObject params) {
            long threshold = Math.max(
                0L,
                getLongParamWithAliases(params, DEFAULT_THRESHOLD_DEMAND, "threshold", "thresholdK"));
            double k1 = Math.max(0D, getDoubleParamWithAliases(params, (double) step, "k1", "k"));
            double defaultK2 = Math.max(k1, k1 * DEFAULT_THRESHOLD_K2_MULTIPLIER);
            double k2 = Math.max(k1, getDoubleParamWithAliases(params, defaultK2, "k2"));

            double demand = (double) demandHeat;
            double rawPrice;
            if (demandHeat <= threshold) {
              rawPrice = (double) basePrice + (k1 * demand);
            } else {
              rawPrice = (double) basePrice
                  + (k1 * (double) threshold)
                  + (k2 * (demand - (double) threshold));
            }
            return toLongPrice(rawPrice);
          }
        });

    strategies.put(
        DynamicAlgorithmType.ELASTICITY_V1,
        new DynamicPricingStrategy() {
          @Override
          public long demandDelta(long currentDemand, int buyQuantity, JsonObject params) {
            return Math.max(1L, buyQuantity);
          }

          @Override
          public long computeRawPrice(long basePrice, long demandHeat, long step, JsonObject params) {
            double eta = getDoubleParamWithAliases(params, DEFAULT_ELASTICITY_ETA, "eta", "elasticity");
            if (!Double.isFinite(eta)) {
              eta = DEFAULT_ELASTICITY_ETA;
            }
            eta = Math.max(0D, eta);

            double epsilon = getDoubleParamWithAliases(params, DEFAULT_ELASTICITY_EPSILON, "epsilon");
            if (!Double.isFinite(epsilon) || epsilon <= 0D) {
              epsilon = DEFAULT_ELASTICITY_EPSILON;
            }
            double d0 = getDoubleParamWithAliases(params, DEFAULT_ELASTICITY_D0, "d0");
            if (!Double.isFinite(d0) || d0 < 0D) {
              d0 = DEFAULT_ELASTICITY_D0;
            }

            double denominator = d0 + epsilon;
            if (denominator <= 0D) {
              denominator = epsilon;
            }
            double ratio = Math.max(0D, ((double) demandHeat + epsilon) / denominator);
            double rawPrice = (double) basePrice * Math.pow(ratio, eta);
            return toLongPrice(rawPrice);
          }
        });

    strategies.put(
        DynamicAlgorithmType.PANIC_BUYING_V1,
        new DynamicPricingStrategy() {
          @Override
          public long demandDelta(long currentDemand, int buyQuantity, JsonObject params) {
            return Math.max(1L, buyQuantity);
          }

          @Override
          public long computeRawPrice(long basePrice, long demandHeat, long step, JsonObject params) {
            double k = Math.max(0D, getDoubleParamWithAliases(params, (double) step, "k"));
            long threshold = Math.max(
                0L,
                getLongParamWithAliases(params, DEFAULT_PANIC_THRESHOLD, "threshold", "panicThreshold"));
            double gamma = Math.max(0D, getDoubleParamWithAliases(params, DEFAULT_PANIC_GAMMA, "gamma"));
            double excess = Math.max(0D, (double) demandHeat - (double) threshold);
            double rawPrice = (double) basePrice
                + (k * (double) demandHeat)
                + (gamma * excess * excess);
            return toLongPrice(rawPrice);
          }
        });

    return strategies;
  }
}

