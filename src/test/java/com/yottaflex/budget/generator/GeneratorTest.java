package com.yottaflex.budget.generator;

import com.yottaflex.budget.TestData;
import com.yottaflex.budget.cli.Cli;
import com.yottaflex.budget.model.TransactionLabel;
import com.yottaflex.budget.util.DatasetIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratorTest {

    @Test
    void producesExactVolumeAndTwelveMonths() {
        var r = TestData.tiny();
        assertThat(r.dataset().users).hasSize(20);
        assertThat(r.dataset().transactions).hasSize(10_000);
        assertThat(r.truth().transactionLabels).hasSize(10_000);
        Set<String> months = r.truth().transactionLabels.stream().map(TransactionLabel::budgetMonth).collect(Collectors.toSet());
        assertThat(months).hasSize(12);
    }

    @Test
    void injectsEveryRequiredEdgeCase() {
        var r = TestData.tiny();
        Set<String> issues = r.truth().transactionLabels.stream().flatMap(l -> Set.of(l.issueType().split("\\|")).stream())
                .collect(Collectors.toSet());
        assertThat(issues).contains("TRANSFER_DOUBLE_COUNT", "REFUND_AS_NEW_EXPENSE", "TIMEZONE_BOUNDARY");
        assertThat(r.truth().recurringSeries).anyMatch(s -> s.issueType().equals("RECURRING_TRUE_SKIP"));
        assertThat(r.truth().budgetLabels).anyMatch(b -> b.issueType().equals("BUDGET_TAXONOMY_MISMATCH"));
    }

    @Test
    void idsFollowNamingConvention() {
        var d = TestData.tiny().dataset();
        assertThat(d.users.get(0).userId()).isEqualTo("USR-000001");
        assertThat(d.accounts.get(0).accountId()).isEqualTo("ACC-USR-000001-01");
        assertThat(d.transactions.get(0).transactionId()).isEqualTo("TXN-2026-00000001");
        assertThat(d.budgets.get(0).budgetId()).matches("BUD-USR-\\d{6}-2026-\\d{2}-[A-Z_]+");
        assertThat(TestData.tiny().truth().recurringSeries.get(0).seriesId()).isEqualTo("REC-000001");
    }

    @Test
    void sameSeedProducesIdenticalFiles(@TempDir Path tmp) throws Exception {
        GeneratorConfig cfg = GeneratorConfig.forScale("tiny", GeneratorConfig.DEFAULT_SEED);
        Cli.generate(cfg, "tiny", tmp.resolve("a"));
        Cli.generate(cfg, "tiny", tmp.resolve("b"));
        for (String f : DatasetIO.inputFiles()) {
            assertThat(Cli.sha256(tmp.resolve("a/input/" + f))).as(f).isEqualTo(Cli.sha256(tmp.resolve("b/input/" + f)));
        }
        for (String f : DatasetIO.groundTruthFiles()) {
            assertThat(Cli.sha256(tmp.resolve("a/ground_truth/" + f))).as(f)
                    .isEqualTo(Cli.sha256(tmp.resolve("b/ground_truth/" + f)));
        }
    }

    @Test
    void differentSeedProducesDifferentData() {
        var other = new SyntheticDataGenerator(GeneratorConfig.forScale("tiny", 7L)).generate();
        assertThat(other.dataset().transactions.get(500)).isNotEqualTo(TestData.tiny().dataset().transactions.get(500));
    }
}
