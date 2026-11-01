package com.yottaflex.budget.generator;

/**
 * Scale parameters. The same generator code produces the starter and full datasets; only these values differ.
 */
public record GeneratorConfig(long seed, int users, int transactions, int year) {

    public static final long DEFAULT_SEED = 20261022L;

    public static GeneratorConfig forScale(String scale, long seed) {
        return switch (scale.toLowerCase()) {
            case "tiny" -> new GeneratorConfig(seed, 20, 10_000, 2026);
            case "starter" -> new GeneratorConfig(seed, 100, 50_000, 2026);
            case "full" -> new GeneratorConfig(seed, 1_000, 500_000, 2026);
            default -> throw new IllegalArgumentException("Unknown scale '" + scale + "' (expected tiny|starter|full)");
        };
    }
}
