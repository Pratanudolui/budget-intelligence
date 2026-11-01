package com.yottaflex.budget.engine;

/**
 * Documented insight thresholds, shared by the generator (expected insights) and the engine (produced insights)
 * so both sides apply the same rule to their own aggregates.
 */
public final class InsightRules {

    public static final double NEAR_BUDGET_RATIO = 0.90;
    public static final double HIGH_SEVERITY_RATIO = 1.25;

    private InsightRules() {
    }

    /** OVER_BUDGET when actual &gt; budget, NEAR_BUDGET when actual is 90-100% of budget, otherwise none. */
    public static String budgetInsightType(long actualCents, long budgetCents) {
        if (budgetCents <= 0) {
            return null;
        }
        double ratio = (double) actualCents / budgetCents;
        if (ratio > 1.0) {
            return "OVER_BUDGET";
        }
        if (ratio >= NEAR_BUDGET_RATIO) {
            return "NEAR_BUDGET";
        }
        return null;
    }

    public static String severity(String type, long actualCents, long budgetCents) {
        if ("OVER_BUDGET".equals(type)) {
            return (double) actualCents / budgetCents > HIGH_SEVERITY_RATIO ? "HIGH" : "MEDIUM";
        }
        return "LOW";
    }
}
