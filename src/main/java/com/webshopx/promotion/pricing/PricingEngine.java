package com.webshopx.promotion.pricing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure, deterministic promotion calculation core. */
public final class PricingEngine {
  public static final String ALGORITHM_VERSION = "promotion-v1";

  public Result calculate(Context context) {
    Objects.requireNonNull(context, "context");
    if (context.lines().isEmpty()) {
      return new Result(List.of(), List.of(), List.of(), Map.of(), 0, 0, stableHash("empty"));
    }
    List<LineState> base = context.lines().stream()
        .sorted(Comparator.comparing(Line::id))
        .map(LineState::new)
        .toList();
    List<Rule> available = new ArrayList<>();
    List<Rejection> rejected = new ArrayList<>();
    for (Rule rule : context.rules().stream().sorted(RULE_ORDER).toList()) {
      String reason = validateCandidate(context, base, rule);
      if (reason == null) {
        available.add(rule);
      } else {
        rejected.add(new Rejection(rule.id(), reason, Map.of()));
      }
    }
    validatePinnedRules(context, available);

    Combination best = search(context, base, available);
    Set<String> selectedIds = best.rules().stream().map(Rule::id).collect(java.util.stream.Collectors.toSet());
    for (Rule rule : available) {
      if (!selectedIds.contains(rule.id())) {
        rejected.add(new Rejection(rule.id(), "LESS_BENEFICIAL_COMBINATION", Map.of()));
      }
    }
    Map<String, CurrencyTotal> totals = totals(best.lines());
    long baseAmount = MathSupport.sumExact(base.stream().map(LineState::amount).toList());
    long payable = MathSupport.sumExact(best.lines().stream().map(LineState::amount).toList());
    String canonical = canonical(best.lines(), best.applications(), rejected, totals);
    return new Result(
        best.lines().stream().map(LineState::toResult).toList(),
        best.applications(),
        rejected.stream().sorted(Comparator.comparing(Rejection::ruleId)).toList(),
        totals,
        baseAmount,
        payable,
        stableHash(canonical));
  }

  private Combination search(Context context, List<LineState> base, List<Rule> candidates) {
    if (candidates.size() > context.maxCandidates()) {
      throw new PricingException("CANDIDATE_LIMIT_EXCEEDED");
    }
    SearchState state = new SearchState(context.maxSearchNodes());
    visit(context, base, candidates, 0, new ArrayList<>(), state);
    if (state.best == null) {
      if (!context.pinnedRuleIds().isEmpty()) {
        throw new PricingException("PINNED_RULE_NOT_APPLICABLE");
      }
      return apply(context, base, List.of());
    }
    return state.best;
  }

  private void visit(
      Context context,
      List<LineState> base,
      List<Rule> candidates,
      int index,
      List<Rule> selected,
      SearchState state) {
    if (++state.nodes > state.maxNodes) {
      throw new PricingException("SEARCH_LIMIT_EXCEEDED");
    }
    if (index == candidates.size()) {
      Combination combination = apply(context, base, selected);
      Set<String> appliedIds = combination.applications().stream()
          .map(Application::ruleId)
          .collect(java.util.stream.Collectors.toSet());
      if (!appliedIds.containsAll(context.pinnedRuleIds())) {
        return;
      }
      if (state.best == null || compare(combination, state.best, context) < 0) {
        state.best = combination;
      }
      return;
    }
    Rule candidate = candidates.get(index);
    if (!context.pinnedRuleIds().contains(candidate.id())) {
      visit(context, base, candidates, index + 1, selected, state);
    }
    if (compatible(selected, candidate)) {
      selected.add(candidate);
      visit(context, base, candidates, index + 1, selected, state);
      selected.remove(selected.size() - 1);
    }
  }

  private void validatePinnedRules(Context context, List<Rule> available) {
    Map<String, Rule> byId = new HashMap<>();
    available.forEach(rule -> byId.put(rule.id(), rule));
    if (!byId.keySet().containsAll(context.pinnedRuleIds())) {
      throw new PricingException("PINNED_RULE_UNAVAILABLE");
    }
    List<Rule> pinned = context.pinnedRuleIds().stream().sorted().map(byId::get).toList();
    for (int index = 0; index < pinned.size(); index++) {
      if (!compatible(pinned.subList(0, index), pinned.get(index))) {
        throw new PricingException("PINNED_RULE_CONFLICT");
      }
    }
  }

  private boolean compatible(List<Rule> selected, Rule candidate) {
    for (Rule existing : selected) {
      if (candidate.exclusiveCheckout() || existing.exclusiveCheckout()) {
        return false;
      }
      if (candidate.exclusiveGroup() != null
          && candidate.exclusiveGroup().equals(existing.exclusiveGroup())) {
        return false;
      }
      if (candidate.slot().equals(existing.slot())) {
        Set<String> overlap = new HashSet<>(candidate.lineIds());
        overlap.retainAll(existing.lineIds());
        if (!overlap.isEmpty()) {
          return false;
        }
      }
    }
    return true;
  }

  private Combination apply(Context context, List<LineState> base, Collection<Rule> rules) {
    List<LineState> states = base.stream().map(LineState::copy).toList();
    Map<String, Long> p0 = new HashMap<>();
    states.forEach(line -> p0.put(line.id, line.amount));
    List<Application> applications = new ArrayList<>();
    for (Rule rule : rules.stream().sorted(RULE_ORDER).toList()) {
      List<LineState> scope = states.stream().filter(line -> rule.lineIds().contains(line.id)).toList();
      if (scope.isEmpty()) {
        continue;
      }
      long thresholdBasis = basis(scope, p0, rule.thresholdBasis());
      int repeats = repeats(rule, thresholdBasis, scope);
      if (repeats == 0) {
        continue;
      }
      long discountBasis = basis(scope, p0, rule.discountBasis());
      long raw = rule.discountAmount() > 0
          ? Math.multiplyExact(rule.discountAmount(), repeats)
          : MathSupport.roundHalfUp(discountBasis, rule.discountBps());
      long capped = rule.maxDiscountAmount() == null ? raw : Math.min(raw, rule.maxDiscountAmount());
      long scopedAmount = scope.stream().mapToLong(LineState::amount).sum();
      String currency = scope.get(0).currency;
      long currencyAmount = states.stream().filter(line -> line.currency.equals(currency))
          .mapToLong(LineState::amount).sum();
      long minimum = rule.allowZeroPayable() ? 0
          : Math.max(context.defaultMinPayable(),
              rule.minPayableOverride() == null ? 0 : rule.minPayableOverride());
      long reducible = Math.min(scopedAmount, Math.max(0, currencyAmount - minimum));
      long actual = Math.min(capped, reducible);
      if (actual <= 0) {
        continue;
      }
      Map<String, Long> weights = new LinkedHashMap<>();
      Map<String, Long> capacities = new LinkedHashMap<>();
      for (LineState line : scope) {
        weights.put(line.id, rule.discountBasis() == Basis.P0 ? p0.get(line.id) : line.amount);
        capacities.put(line.id, line.amount);
      }
      Map<String, Long> allocation = AllocationEngine.allocate(actual, weights, capacities);
      for (LineState line : scope) {
        line.amount = Math.subtractExact(line.amount, allocation.getOrDefault(line.id, 0L));
      }
      Funding funding = Funding.split(actual, rule.fundingMode(), rule.platformShareBps());
      applications.add(new Application(rule.id(), rule.layer(), rule.slot(), thresholdBasis,
          discountBasis, actual, allocation, funding, rule.userCouponId(), rule.expiresAt()));
    }
    return new Combination(states, List.copyOf(applications), List.copyOf(rules));
  }

  private int compare(Combination left, Combination right, Context context) {
    long leftPayable = left.lines().stream().mapToLong(LineState::amount).sum();
    long rightPayable = right.lines().stream().mapToLong(LineState::amount).sum();
    int result = Long.compare(leftPayable, rightPayable);
    if (result != 0) {
      return result;
    }
    Instant leftExpiry = left.rules().stream().map(Rule::expiresAt).filter(Objects::nonNull).min(Instant::compareTo)
        .orElse(Instant.MAX);
    Instant rightExpiry = right.rules().stream().map(Rule::expiresAt).filter(Objects::nonNull).min(Instant::compareTo)
        .orElse(Instant.MAX);
    result = leftExpiry.compareTo(rightExpiry);
    if (result != 0) {
      return result;
    }
    long leftPinned = left.rules().stream().filter(rule -> context.pinnedRuleIds().contains(rule.id())).count();
    long rightPinned = right.rules().stream().filter(rule -> context.pinnedRuleIds().contains(rule.id())).count();
    result = Long.compare(rightPinned, leftPinned);
    if (result != 0) {
      return result;
    }
    result = Integer.compare(left.rules().size(), right.rules().size());
    if (result != 0) {
      return result;
    }
    return left.rules().stream().map(Rule::id).sorted().toList().toString()
        .compareTo(right.rules().stream().map(Rule::id).sorted().toList().toString());
  }

  private String validateCandidate(Context context, List<LineState> lines, Rule rule) {
    if (context.disabledRuleIds().contains(rule.id())) {
      return "DISABLED";
    }
    if (rule.startsAt() != null && context.evaluationTime().isBefore(rule.startsAt())) {
      return "NOT_STARTED";
    }
    if (rule.expiresAt() != null && !context.evaluationTime().isBefore(rule.expiresAt())) {
      return "EXPIRED";
    }
    if (rule.requiredEntitlement() != null
        && !context.entitlements().contains(rule.requiredEntitlement())) {
      return "ENTITLEMENT_REQUIRED";
    }
    List<LineState> scope = lines.stream().filter(line -> rule.lineIds().contains(line.id)).toList();
    if (scope.isEmpty()) {
      return "SCOPE_MISMATCH";
    }
    if (scope.stream().map(line -> line.currency).distinct().count() > 1) {
      return "CROSS_CURRENCY_SCOPE";
    }
    long threshold = scope.stream().mapToLong(LineState::amount).sum();
    if (rule.thresholdType() == ThresholdType.AMOUNT && threshold < rule.thresholdValue()) {
      return "THRESHOLD_NOT_MET";
    }
    long quantity = scope.stream().mapToLong(line -> line.quantity).sum();
    if (rule.thresholdType() == ThresholdType.QUANTITY && quantity < rule.thresholdValue()) {
      return "QUANTITY_NOT_MET";
    }
    return null;
  }

  private int repeats(Rule rule, long basis, List<LineState> lines) {
    long measured = rule.thresholdType() == ThresholdType.QUANTITY
        ? lines.stream().mapToLong(line -> line.quantity).sum()
        : basis;
    if (rule.thresholdType() == ThresholdType.NONE) {
      return 1;
    }
    if (measured < rule.thresholdValue()) {
      return 0;
    }
    if (rule.repeatMode() != RepeatMode.EVERY_FULL_THRESHOLD) {
      return 1;
    }
    long count = measured / rule.thresholdValue();
    return Math.toIntExact(Math.min(count, rule.maxRepeatCount()));
  }

  private long basis(List<LineState> scope, Map<String, Long> p0, Basis basis) {
    return MathSupport.sumExact(scope.stream()
        .map(line -> basis == Basis.P0 ? p0.get(line.id) : line.amount)
        .toList());
  }

  private Map<String, CurrencyTotal> totals(List<LineState> lines) {
    Map<String, long[]> values = new java.util.TreeMap<>();
    for (LineState line : lines) {
      long[] total = values.computeIfAbsent(line.currency, ignored -> new long[2]);
      total[0] = Math.addExact(total[0], line.baseAmount);
      total[1] = Math.addExact(total[1], line.amount);
    }
    Map<String, CurrencyTotal> result = new LinkedHashMap<>();
    values.forEach((currency, value) -> result.put(currency,
        new CurrencyTotal(currency, value[0], Math.subtractExact(value[0], value[1]), value[1])));
    return Map.copyOf(result);
  }

  private String canonical(
      List<LineState> lines,
      List<Application> applications,
      List<Rejection> rejections,
      Map<String, CurrencyTotal> totals) {
    return ALGORITHM_VERSION + "|" + lines.stream().map(LineState::canonical).toList()
        + "|" + applications + "|" + rejections + "|" + new java.util.TreeMap<>(totals);
  }

  public static String stableHash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static final Comparator<Rule> RULE_ORDER = Comparator.comparingInt(Rule::layer)
      .thenComparingInt(Rule::priority).thenComparing(Rule::id);

  public record Context(
      Instant evaluationTime,
      List<Line> lines,
      List<Rule> rules,
      Set<String> entitlements,
      Set<String> pinnedRuleIds,
      Set<String> disabledRuleIds,
      long defaultMinPayable,
      int maxCandidates,
      int maxSearchNodes) {
    public Context {
      evaluationTime = Objects.requireNonNull(evaluationTime);
      lines = List.copyOf(lines);
      rules = List.copyOf(rules);
      entitlements = Set.copyOf(entitlements);
      pinnedRuleIds = Set.copyOf(pinnedRuleIds);
      disabledRuleIds = Set.copyOf(disabledRuleIds);
      if (defaultMinPayable < 0 || maxCandidates < 1 || maxSearchNodes < 1) {
        throw new IllegalArgumentException("Invalid pricing limits");
      }
    }
  }

  public record Line(String id, String currency, long baseAmount, int quantity, String sellerId) {
    public Line {
      if (id == null || id.isBlank() || currency == null || currency.isBlank()
          || baseAmount < 0 || quantity <= 0) {
        throw new IllegalArgumentException("Invalid pricing line");
      }
    }
  }

  public record Rule(
      String id,
      int layer,
      int priority,
      String slot,
      Set<String> lineIds,
      ThresholdType thresholdType,
      long thresholdValue,
      Basis thresholdBasis,
      Basis discountBasis,
      RepeatMode repeatMode,
      int maxRepeatCount,
      long discountAmount,
      int discountBps,
      Long maxDiscountAmount,
      FundingMode fundingMode,
      int platformShareBps,
      Long minPayableOverride,
      boolean allowZeroPayable,
      boolean exclusiveCheckout,
      String exclusiveGroup,
      String requiredEntitlement,
      Long userCouponId,
      Instant startsAt,
      Instant expiresAt) {
    public Rule {
      lineIds = Set.copyOf(lineIds);
      if (id == null || id.isBlank() || slot == null || slot.isBlank() || thresholdValue < 0
          || maxRepeatCount < 1 || discountAmount < 0 || discountBps < 0 || discountBps > 10000
          || platformShareBps < 0 || platformShareBps > 10000) {
        throw new IllegalArgumentException("Invalid pricing rule");
      }
      if (discountAmount == 0 && discountBps == 0) {
        throw new IllegalArgumentException("Rule has no discount");
      }
    }
  }

  public enum ThresholdType { NONE, AMOUNT, QUANTITY }
  public enum Basis { P0, CURRENT }
  public enum RepeatMode { ONCE, HIGHEST_TIER, EVERY_FULL_THRESHOLD }
  public enum FundingMode { PLATFORM, SELLER, SHARED }

  public record Funding(long platformAmount, long sellerAmount) {
    static Funding split(long total, FundingMode mode, int platformShareBps) {
      return switch (mode) {
        case PLATFORM -> new Funding(total, 0);
        case SELLER -> new Funding(0, total);
        case SHARED -> {
          long platform = MathSupport.roundHalfUp(total, platformShareBps);
          yield new Funding(platform, Math.subtractExact(total, platform));
        }
      };
    }
  }

  public record Application(
      String ruleId,
      int layer,
      String slot,
      long thresholdBasisAmount,
      long discountBasisAmount,
      long discountAmount,
      Map<String, Long> allocations,
      Funding funding,
      Long userCouponId,
      Instant expiresAt) {
    public Application { allocations = Map.copyOf(allocations); }
  }

  public record LineResult(String id, String currency, long baseAmount, long finalAmount, int quantity,
                           String sellerId, List<Long> unitFinalAmounts) {}
  public record CurrencyTotal(String currency, long baseAmount, long discountAmount, long payableAmount) {}
  public record Rejection(String ruleId, String reasonCode, Map<String, Object> details) {}
  public record Result(List<LineResult> lines, List<Application> applications, List<Rejection> rejections,
                       Map<String, CurrencyTotal> currencyTotals, long baseAmount, long payableAmount,
                       String resultHash) {}

  private static final class LineState {
    private final String id;
    private final String currency;
    private final long baseAmount;
    private final int quantity;
    private final String sellerId;
    private long amount;

    private LineState(Line line) {
      id = line.id(); currency = line.currency(); baseAmount = line.baseAmount();
      quantity = line.quantity(); sellerId = line.sellerId(); amount = baseAmount;
    }

    private LineState copy() { return new LineState(new Line(id, currency, amount, quantity, sellerId), baseAmount); }
    private LineState(Line line, long originalBase) {
      id = line.id(); currency = line.currency(); baseAmount = originalBase;
      quantity = line.quantity(); sellerId = line.sellerId(); amount = line.baseAmount();
    }
    private long amount() { return amount; }
    private String canonical() { return id + ":" + currency + ":" + baseAmount + ":" + amount + ":" + quantity; }
    private LineResult toResult() {
      return new LineResult(id, currency, baseAmount, amount, quantity, sellerId,
          AllocationEngine.allocateUnits(amount, quantity));
    }
  }

  private record Combination(List<LineState> lines, List<Application> applications, List<Rule> rules) {}
  private static final class SearchState {
    private final int maxNodes; private int nodes; private Combination best;
    private SearchState(int maxNodes) { this.maxNodes = maxNodes; }
  }

  public static final class PricingException extends RuntimeException {
    private final String code;
    public PricingException(String code) { super(code); this.code = code; }
    public String code() { return code; }
  }
}
