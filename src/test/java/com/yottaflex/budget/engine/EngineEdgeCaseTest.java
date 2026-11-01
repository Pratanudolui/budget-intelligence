package com.yottaflex.budget.engine;

import com.yottaflex.budget.TestData;
import com.yottaflex.budget.evaluation.Evaluator;
import com.yottaflex.budget.model.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EngineEdgeCaseTest {

    private static EngineResult result;
    private static Map<String, Object> report;

    @BeforeAll
    static void run() {
        var data = TestData.tiny();
        result = new BudgetIntelligenceEngine().run(data.dataset());
        report = new Evaluator().evaluate(data.dataset(), data.truth(), result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(String name) {
        return (Map<String, Object>) report.get(name);
    }

    @SuppressWarnings("unchecked")
    private static double rate(String issue) {
        return ((Number) ((Map<String, Object>) section("edge_cases").get(issue)).get("rate")).doubleValue();
    }

    @Test
    void transfersAreNeverCountedAsSpending() {
        assertThat(rate("TRANSFER_DOUBLE_COUNT")).isEqualTo(1.0);
        assertThat(result.classifications).filteredOn(EngineResult.Classification::transfer)
                .allMatch(c -> c.spendImpactCents() == 0);
    }

    @Test
    void timezoneBoundaryUsesLocalMonth() {
        assertThat(rate("TIMEZONE_BOUNDARY")).isEqualTo(1.0);
    }

    @Test
    void refundsReduceSpendInsteadOfAddingExpense() {
        assertThat(rate("REFUND_AS_NEW_EXPENSE")).isGreaterThan(0.85);
        assertThat(result.classifications).filteredOn(EngineResult.Classification::refund)
                .allMatch(c -> c.spendImpactCents() <= 0);
    }

    @Test
    void budgetTaxonomyMismatchesAreMappedOrFlagged() {
        assertThat(rate("BUDGET_TAXONOMY_MISMATCH")).isEqualTo(1.0);
    }

    @Test
    void recurringFalseSkipsAreNotFlagged() {
        assertThat(((Number) section("recurring").get("shifted_postings_wrongly_flagged_missed")).intValue()).isZero();
    }

    @Test
    void engineBeatsNaiveBaselineOnMonthlyActuals() {
        Map<String, Object> actuals = section("monthly_actuals");
        @SuppressWarnings("unchecked")
        double engine = ((Number) ((Map<String, Object>) actuals.get("engine")).get("mae_dollars")).doubleValue();
        @SuppressWarnings("unchecked")
        double naive = ((Number) ((Map<String, Object>) actuals.get("naive_baseline")).get("mae_dollars")).doubleValue();
        assertThat(engine).isLessThan(naive);
    }

    @Test
    void categorizationMetricsAreCalculable() {
        Map<String, Object> cat = section("categorization");
        assertThat(((Number) cat.get("accuracy")).doubleValue()).isGreaterThan(0.8);
        assertThat(((Number) cat.get("macro_f1")).doubleValue()).isGreaterThan(0.8);
    }

    @Test
    void unknownMerchantWithoutMccFallsBackToUncategorizedException() {
        Dataset d = new Dataset();
        var base = TestData.tiny().dataset();
        d.users.add(base.users.get(0));
        d.accounts.add(base.accounts.get(0));
        d.categories.addAll(base.categories);
        d.categoryRules.addAll(base.categoryRules);
        d.transactions.add(new Transaction("TXN-2026-00000001", base.accounts.get(0).accountId(), null,
                Instant.parse("2026-03-10T15:00:00Z"), -1_234, "USD", "POS MYSTERY SHOP 42", "CARD_PURCHASE", null, 0));
        EngineResult r = new BudgetIntelligenceEngine().run(d);
        assertThat(r.classifications.get(0).categoryCode()).isEqualTo("UNCT");
        assertThat(r.exceptions).extracting(EngineResult.ExceptionItem::exceptionType)
                .containsExactly("UNCATEGORIZED_TRANSACTION");
        assertThat(r.classifications.get(0).localDate()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(List.of(r.exceptions.get(0).exceptionId())).containsExactly("EXC-0000001");
    }
}
