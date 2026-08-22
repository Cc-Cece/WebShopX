package com.webshopx.promotion.pricing;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Largest-remainder allocation with per-line capacity and stable identifiers. */
public final class AllocationEngine {
  private AllocationEngine() {}

  public static Map<String, Long> allocate(
      long amount, Map<String, Long> weights, Map<String, Long> capacities) {
    if (amount < 0) throw new IllegalArgumentException("amount");
    List<String> ids = weights.keySet().stream().sorted().toList();
    Map<String, Long> result = new LinkedHashMap<>();
    ids.forEach(id -> result.put(id, 0L));
    long remaining = amount;
    List<String> active = new ArrayList<>(ids);
    while (remaining > 0 && !active.isEmpty()) {
      long totalWeight = active.stream().mapToLong(id -> Math.max(0, weights.getOrDefault(id, 0L))).sum();
      if (totalWeight <= 0) totalWeight = active.size();
      long roundAmount = remaining;
      List<Remainder> remainders = new ArrayList<>();
      long distributed = 0;
      for (String id : active) {
        long weight = Math.max(0, weights.getOrDefault(id, 0L));
        if (weight == 0 && active.stream().allMatch(key -> weights.getOrDefault(key, 0L) == 0)) weight = 1;
        BigInteger numerator = BigInteger.valueOf(roundAmount).multiply(BigInteger.valueOf(weight));
        BigInteger[] division = numerator.divideAndRemainder(BigInteger.valueOf(totalWeight));
        long capacityLeft = capacities.getOrDefault(id, Long.MAX_VALUE) - result.get(id);
        long share = Math.min(division[0].longValueExact(), Math.max(0, capacityLeft));
        result.put(id, Math.addExact(result.get(id), share));
        distributed = Math.addExact(distributed, share);
        remainders.add(new Remainder(id, division[1], capacityLeft - share));
      }
      remaining -= distributed;
      remainders.sort(Comparator.comparing(Remainder::remainder).reversed().thenComparing(Remainder::id));
      boolean progressed = distributed > 0;
      for (Remainder remainder : remainders) {
        if (remaining == 0) break;
        if (remainder.capacityLeft() > 0) {
          result.put(remainder.id(), Math.addExact(result.get(remainder.id()), 1));
          remaining--;
          progressed = true;
        }
      }
      active.removeIf(id -> result.get(id) >= capacities.getOrDefault(id, Long.MAX_VALUE));
      if (!progressed) break;
    }
    return Map.copyOf(result);
  }

  public static List<Long> allocateUnits(long amount, int quantity) {
    if (amount < 0 || quantity <= 0) throw new IllegalArgumentException("unit allocation");
    long base = amount / quantity;
    int remainder = Math.toIntExact(amount % quantity);
    List<Long> units = new ArrayList<>(quantity);
    for (int index = 0; index < quantity; index++) units.add(base + (index < remainder ? 1 : 0));
    return List.copyOf(units);
  }

  private record Remainder(String id, BigInteger remainder, long capacityLeft) {}
}
