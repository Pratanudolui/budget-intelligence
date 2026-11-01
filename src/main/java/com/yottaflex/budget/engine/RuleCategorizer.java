package com.yottaflex.budget.engine;

import com.yottaflex.budget.model.CategoryRule;
import com.yottaflex.budget.model.Transaction;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Applies data-driven {@link CategoryRule}s in priority order; first active match wins. */
public class RuleCategorizer {

    public static final String FALLBACK_CATEGORY = "UNCT";

    public record Match(String categoryCode, String ruleId, String matchType, double confidence) {
    }

    private record Compiled(CategoryRule rule, Pattern pattern) {
    }

    private final List<Compiled> rules = new ArrayList<>();

    public RuleCategorizer(List<CategoryRule> categoryRules) {
        categoryRules.stream()
                .filter(r -> "ACTIVE".equals(r.status()))
                .sorted(Comparator.comparingInt(CategoryRule::priority).thenComparing(CategoryRule::ruleId))
                .forEach(r -> rules.add(new Compiled(r, r.pattern() == null ? null : Pattern.compile(r.pattern()))));
    }

    public Match categorize(Transaction t, LocalDate localDate) {
        long abs = Math.abs(t.amountCents());
        for (Compiled c : rules) {
            CategoryRule r = c.rule();
            if (r.effectiveFrom() != null && localDate.isBefore(r.effectiveFrom())) {
                continue;
            }
            if (r.effectiveTo() != null && localDate.isAfter(r.effectiveTo())) {
                continue;
            }
            boolean hit = switch (r.matchType()) {
                case "TXN_TYPE" -> r.txnType().equals(t.txnType());
                case "DESCRIPTOR_REGEX" -> t.descriptor() != null && c.pattern().matcher(t.descriptor()).find();
                case "MERCHANT" -> r.merchantId().equals(t.merchantId());
                case "MERCHANT_AMOUNT" -> r.merchantId().equals(t.merchantId())
                        && (r.minAbsCents() == null || abs >= r.minAbsCents())
                        && (r.maxAbsCents() == null || abs <= r.maxAbsCents());
                case "MCC" -> r.mcc().equals(t.mcc());
                default -> false;
            };
            if (hit) {
                return new Match(r.categoryCode(), r.ruleId(), r.matchType(), confidenceFor(r.matchType()));
            }
        }
        return new Match(FALLBACK_CATEGORY, null, "FALLBACK", 0.0);
    }

    private static double confidenceFor(String matchType) {
        return switch (matchType) {
            case "TXN_TYPE", "MERCHANT" -> 0.95;
            case "DESCRIPTOR_REGEX" -> 0.90;
            case "MERCHANT_AMOUNT" -> 0.70;
            case "MCC" -> 0.60;
            default -> 0.0;
        };
    }
}
