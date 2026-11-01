package com.yottaflex.budget;

import com.yottaflex.budget.generator.GeneratorConfig;
import com.yottaflex.budget.generator.SyntheticDataGenerator;

/** Shared tiny dataset (20 users / 10,000 transactions), generated once per JVM. */
public final class TestData {

    private static SyntheticDataGenerator.Result tiny;

    private TestData() {
    }

    public static synchronized SyntheticDataGenerator.Result tiny() {
        if (tiny == null) {
            tiny = new SyntheticDataGenerator(GeneratorConfig.forScale("tiny", GeneratorConfig.DEFAULT_SEED)).generate();
        }
        return tiny;
    }
}
