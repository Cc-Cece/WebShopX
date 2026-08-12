package com.webshopx.promotion.pricing;

import java.math.BigInteger;
import java.util.Collection;

public final class MathSupport {
  private MathSupport() {}

  public static long roundHalfUp(long amount, int bps) {
    if (amount < 0 || bps < 0) throw new IllegalArgumentException("negative money");
    BigInteger numerator = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(bps));
    return numerator.add(BigInteger.valueOf(5000)).divide(BigInteger.valueOf(10000)).longValueExact();
  }

  public static long sumExact(Collection<Long> amounts) {
    long total = 0;
    for (long amount : amounts) total = Math.addExact(total, amount);
    return total;
  }
}
