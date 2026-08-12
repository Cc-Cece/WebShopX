package com.webshopx.promotion.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PricingEngineTest {
  private final PricingEngine engine = new PricingEngine();

  @Test
  void selectsBestConflictingCouponAndAppliesAutomaticPromotion() {
    var lines = List.of(new PricingEngine.Line("A", "SHOP_COIN", 120, 1, null),
        new PricingEngine.Line("B", "SHOP_COIN", 80, 1, null));
    var automatic = rule("auto", 4, "PLATFORM_PROMOTION", Set.of("A", "B"), 200, 20);
    var c1 = rule("c1", 5, "PLATFORM_COUPON", Set.of("A", "B"), 150, 30);
    var c2 = rule("c2", 5, "PLATFORM_COUPON", Set.of("A"), 0, 25);
    var result = engine.calculate(context(lines, List.of(automatic, c2, c1), Set.of()));
    assertEquals(150, result.payableAmount());
    assertEquals(List.of("auto", "c1"), result.applications().stream().map(PricingEngine.Application::ruleId).toList());
    assertEquals(50, result.applications().stream().mapToLong(PricingEngine.Application::discountAmount).sum());
  }

  @Test
  void disjointCouponsInSameSlotCanCoexist() {
    var result = engine.calculate(context(
        List.of(new PricingEngine.Line("A", "SHOP_COIN", 100, 1, null),
            new PricingEngine.Line("B", "SHOP_COIN", 100, 1, null)),
        List.of(rule("a", 5, "PLATFORM_COUPON", Set.of("A"), 0, 10),
            rule("b", 5, "PLATFORM_COUPON", Set.of("B"), 0, 20)), Set.of()));
    assertEquals(170, result.payableAmount());
  }

  @Test
  void memberSlotRequiresEntitlement() {
    var member = new PricingEngine.Rule("member", 5, 0, "MEMBER_COUPON", Set.of("A"),
        PricingEngine.ThresholdType.NONE, 0, PricingEngine.Basis.P0, PricingEngine.Basis.CURRENT,
        PricingEngine.RepeatMode.ONCE, 1, 20, 0, null, PricingEngine.FundingMode.PLATFORM, 10000,
        null, false, false, null, "MEMBER_STACKING", 2L, null, null);
    var normal = rule("normal", 5, "PLATFORM_COUPON", Set.of("A"), 0, 30);
    var line = List.of(new PricingEngine.Line("A", "SHOP_COIN", 200, 1, null));
    assertEquals(170, engine.calculate(context(line, List.of(normal, member), Set.of())).payableAmount());
    assertEquals(150, engine.calculate(context(line, List.of(normal, member), Set.of("MEMBER_STACKING"))).payableAmount());
  }

  @Test
  void allocationAndUnitRefundRemainExact() {
    var allocated = AllocationEngine.allocate(31, java.util.Map.of("A", 101L, "B", 99L),
        java.util.Map.of("A", 100L, "B", 98L));
    assertEquals(16, allocated.get("A"));
    assertEquals(15, allocated.get("B"));
    assertEquals(List.of(33L, 33L, 33L), AllocationEngine.allocateUnits(99, 3));
  }

  @Test
  void overflowIsRejected() {
    assertThrows(ArithmeticException.class, () -> MathSupport.sumExact(List.of(Long.MAX_VALUE, 1L)));
  }

  @Test
  void shuffledInputHasStableHash() {
    var a = new PricingEngine.Line("A", "SHOP_COIN", 100, 1, null);
    var b = new PricingEngine.Line("B", "SHOP_COIN", 100, 1, null);
    var r1 = rule("a", 5, "PLATFORM_COUPON", Set.of("A"), 0, 10);
    var r2 = rule("b", 5, "PLATFORM_COUPON", Set.of("B"), 0, 20);
    assertEquals(engine.calculate(context(List.of(a, b), List.of(r1, r2), Set.of())).resultHash(),
        engine.calculate(context(List.of(b, a), List.of(r2, r1), Set.of())).resultHash());
  }

  @Test
  void sharedFundingAndMinimumPayableRemainExact() {
    var shared = new PricingEngine.Rule("shared", 3, 0, "PLATFORM_PROMOTION", Set.of("A"),
        PricingEngine.ThresholdType.NONE, 0, PricingEngine.Basis.P0,
        PricingEngine.Basis.CURRENT, PricingEngine.RepeatMode.ONCE, 1, 200, 0, null,
        PricingEngine.FundingMode.SHARED, 2500, 10L, false, false, null, null, null,
        null, null);
    var result = engine.calculate(context(
        List.of(new PricingEngine.Line("A", "SHOP_COIN", 100, 2, null)),
        List.of(shared), Set.of()));
    assertEquals(10, result.payableAmount());
    assertEquals(23, result.applications().get(0).funding().platformAmount());
    assertEquals(67, result.applications().get(0).funding().sellerAmount());
    assertEquals(List.of(5L, 5L), result.lines().get(0).unitFinalAmounts());
  }

  @Test
  void allowZeroPayableCanFullyDiscountLine() {
    var free = new PricingEngine.Rule("free", 3, 0, "PLATFORM_PROMOTION", Set.of("A"),
        PricingEngine.ThresholdType.NONE, 0, PricingEngine.Basis.P0,
        PricingEngine.Basis.CURRENT, PricingEngine.RepeatMode.ONCE, 1, 0, 10000, null,
        PricingEngine.FundingMode.PLATFORM, 10000, null, true, false, null, null, null,
        null, null);
    assertEquals(0, engine.calculate(context(
        List.of(new PricingEngine.Line("A", "SHOP_COIN", 99, 3, null)),
        List.of(free), Set.of())).payableAmount());
  }

  @Test
  void repeatedThresholdUsesOnlyFullThresholds() {
    var repeated = new PricingEngine.Rule("repeat", 3, 0, "PLATFORM_PROMOTION", Set.of("A"),
        PricingEngine.ThresholdType.AMOUNT, 100, PricingEngine.Basis.P0,
        PricingEngine.Basis.CURRENT, PricingEngine.RepeatMode.EVERY_FULL_THRESHOLD, 3,
        10, 0, null, PricingEngine.FundingMode.PLATFORM, 10000, null, false,
        false, null, null, null, null, null);
    assertEquals(230, engine.calculate(context(
        List.of(new PricingEngine.Line("A", "SHOP_COIN", 250, 1, null)),
        List.of(repeated), Set.of())).payableAmount());
  }

  private PricingEngine.Context context(List<PricingEngine.Line> lines, List<PricingEngine.Rule> rules,
                                        Set<String> entitlements) {
    return new PricingEngine.Context(Instant.parse("2026-08-13T00:00:00Z"), lines, rules,
        entitlements, Set.of(), Set.of(), 1, 50, 1_000_000);
  }

  private PricingEngine.Rule rule(String id, int layer, String slot, Set<String> lineIds,
                                  long threshold, long discount) {
    return new PricingEngine.Rule(id, layer, 0, slot, lineIds,
        threshold == 0 ? PricingEngine.ThresholdType.NONE : PricingEngine.ThresholdType.AMOUNT,
        threshold, PricingEngine.Basis.P0, PricingEngine.Basis.CURRENT, PricingEngine.RepeatMode.ONCE,
        1, discount, 0, null, PricingEngine.FundingMode.PLATFORM, 10000, null, false,
        false, null, null, null, null, null);
  }
}
