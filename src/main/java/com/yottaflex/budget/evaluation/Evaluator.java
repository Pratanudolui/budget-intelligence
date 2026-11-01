package com.yottaflex.budget.evaluation;

import com.yottaflex.budget.engine.EngineResult;
import com.yottaflex.budget.engine.EngineResult.Classification;
import com.yottaflex.budget.engine.EngineResult.DetectedSeries;
import com.yottaflex.budget.engine.RuleCategorizer;
import com.yottaflex.budget.model.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Scores an {@link EngineResult} against the generator's ground truth. Also scores a deliberately naive baseline
 * (no transfer pairing, refunds by transaction type, UTC months) so the effect of each edge-case rule is visible.
 */
public class Evaluator {

    public Map<String, Object> evaluate(Dataset d, GroundTruth g, EngineResult r) {
        Map<String, TransactionLabel> labels = new HashMap<>();
        g.transactionLabels.forEach(l -> labels.put(l.transactionId(), l));
        Map<String, Classification> cls = new HashMap<>();
        r.classifications.forEach(c -> cls.put(c.transactionId(), c));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("transactions_evaluated", r.classifications.size());
        report.put("categorization", categorization(labels, r));
        report.put("transfers", binary(labels, cls, TransactionLabel::transfer, Classification::transfer));
        Map<String, Object> refunds = binary(labels, cls, TransactionLabel::refund, Classification::refund);
        long linkedCorrect = r.refundLinks.stream()
                .filter(l -> l.originalTransactionId().equals(labels.get(l.refundTransactionId()).refundOfTransactionId()))
                .count();
        refunds.put("links", r.refundLinks.size());
        refunds.put("links_pointing_to_true_original", linkedCorrect);
        report.put("refunds", refunds);
        report.put("recurring", recurring(g, r));
        report.put("timezone", timezone(labels, cls, d));
        report.put("budget_taxonomy", budgetTaxonomy(g, r));
        report.put("monthly_actuals", actuals(d, g, r));
        report.put("insights", insights(g, r));
        report.put("edge_cases", edgeCases(labels, cls, g, r));
        report.put("exceptions_by_type", exceptionCounts(r));
        return report;
    }

    // ------------------------------------------------------------------ categorization
    private Map<String, Object> categorization(Map<String, TransactionLabel> labels, EngineResult r) {
        Map<String, int[]> perCat = new TreeMap<>(); // tp, fp, fn
        int correct = 0;
        int nonTransfer = 0;
        int nonTransferCorrect = 0;
        int uncategorized = 0;
        Map<String, Integer> confusion = new HashMap<>();
        for (Classification c : r.classifications) {
            TransactionLabel l = labels.get(c.transactionId());
            String truth = l.trueCategoryCode();
            String pred = c.categoryCode();
            if (RuleCategorizer.FALLBACK_CATEGORY.equals(pred)) {
                uncategorized++;
            }
            if (truth.equals(pred)) {
                correct++;
                perCat.computeIfAbsent(truth, k -> new int[3])[0]++;
            } else {
                perCat.computeIfAbsent(pred, k -> new int[3])[1]++;
                perCat.computeIfAbsent(truth, k -> new int[3])[2]++;
                confusion.merge(truth + "->" + pred, 1, Integer::sum);
            }
            if (!l.transfer()) {
                nonTransfer++;
                if (truth.equals(pred)) {
                    nonTransferCorrect++;
                }
            }
        }
        Map<String, Object> per = new LinkedHashMap<>();
        double f1Sum = 0;
        int f1Count = 0;
        for (Map.Entry<String, int[]> e : perCat.entrySet()) {
            int[] v = e.getValue();
            double p = ratio(v[0], v[0] + v[1]);
            double rc = ratio(v[0], v[0] + v[2]);
            double f1 = p + rc == 0 ? 0 : 2 * p * rc / (p + rc);
            if (!RuleCategorizer.FALLBACK_CATEGORY.equals(e.getKey())) {
                f1Sum += f1;
                f1Count++;
            }
            per.put(e.getKey(), Map.of("support", v[0] + v[2], "precision", round(p), "recall", round(rc),
                    "f1", round(f1)));
        }
        List<Map.Entry<String, Integer>> top = new ArrayList<>(confusion.entrySet());
        top.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        Map<String, Integer> topConfusions = new LinkedHashMap<>();
        top.stream().limit(8).forEach(e -> topConfusions.put(e.getKey(), e.getValue()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accuracy", round(ratio(correct, r.classifications.size())));
        out.put("accuracy_excluding_transfers", round(ratio(nonTransferCorrect, nonTransfer)));
        out.put("macro_f1", round(f1Count == 0 ? 0 : f1Sum / f1Count));
        out.put("coverage", round(1 - ratio(uncategorized, r.classifications.size())));
        out.put("uncategorized", uncategorized);
        out.put("top_confusions", topConfusions);
        out.put("per_category", per);
        return out;
    }

    private Map<String, Object> binary(Map<String, TransactionLabel> labels, Map<String, Classification> cls,
                                       java.util.function.Predicate<TransactionLabel> truthFn,
                                       java.util.function.Predicate<Classification> predFn) {
        int tp = 0, fp = 0, fn = 0;
        for (Map.Entry<String, Classification> e : cls.entrySet()) {
            boolean t = truthFn.test(labels.get(e.getKey()));
            boolean p = predFn.test(e.getValue());
            if (t && p) tp++;
            else if (p) fp++;
            else if (t) fn++;
        }
        return prf(tp, fp, fn);
    }

    // ------------------------------------------------------------------ recurring
    private Map<String, Object> recurring(GroundTruth g, EngineResult r) {
        Map<String, RecurringSeriesTruth> truth = new HashMap<>();
        g.recurringSeries.forEach(s -> truth.put(s.accountId() + "|" + s.merchantId(), s));
        Map<String, DetectedSeries> detected = new HashMap<>();
        r.recurringSeries.forEach(s -> detected.putIfAbsent(s.accountId() + "|" + s.merchantId(), s));
        int tp = 0, fp = 0, cadenceOk = 0;
        for (Map.Entry<String, DetectedSeries> e : detected.entrySet()) {
            RecurringSeriesTruth t = truth.get(e.getKey());
            if (t != null) {
                tp++;
                if (t.cadence().equals(e.getValue().cadence())) {
                    cadenceOk++;
                }
            } else {
                fp++;
            }
        }
        int fn = truth.size() - tp;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("series_truth", truth.size());
        out.put("series_detected", r.recurringSeries.size());
        out.put("series_detection", prf(tp, fp, fn));
        out.put("cadence_accuracy_on_matched", round(ratio(cadenceOk, tp)));

        // Missed-occurrence detection (true skips) and false-skip avoidance (shifted postings)
        Set<String> trueMissed = new HashSet<>();
        int shiftedTotal = 0, shiftedFlagged = 0;
        for (RecurringSeriesTruth t : g.recurringSeries) {
            if (t.missedDates() != null) {
                for (String m : t.missedDates().split(";")) {
                    trueMissed.add(t.accountId() + "|" + t.merchantId() + "|" + YearMonth.from(LocalDate.parse(m)));
                }
            }
        }
        Set<String> predMissed = new HashSet<>();
        for (DetectedSeries s : r.recurringSeries) {
            for (LocalDate m : s.missedDates()) {
                predMissed.add(s.accountId() + "|" + s.merchantId() + "|" + YearMonth.from(m));
            }
        }
        for (RecurringSeriesTruth t : g.recurringSeries) {
            if (t.shiftedDates() != null) {
                for (String sd : t.shiftedDates().split(";")) {
                    shiftedTotal++;
                    if (predMissed.contains(t.accountId() + "|" + t.merchantId() + "|" + YearMonth.from(LocalDate.parse(sd)))) {
                        shiftedFlagged++;
                    }
                }
            }
        }
        int mtp = 0;
        for (String k : predMissed) {
            if (trueMissed.contains(k)) mtp++;
        }
        out.put("missed_occurrence_detection", prf(mtp, predMissed.size() - mtp, trueMissed.size() - mtp));
        out.put("shifted_postings_total", shiftedTotal);
        out.put("shifted_postings_wrongly_flagged_missed", shiftedFlagged);
        out.put("naive_month_bucket_would_flag", shiftedTotal);

        Set<String> truePrice = new HashSet<>();
        g.recurringSeries.stream().filter(s -> s.priceChangeDate() != null)
                .forEach(s -> truePrice.add(s.accountId() + "|" + s.merchantId()));
        int ptp = 0, pfp = 0;
        for (DetectedSeries s : r.recurringSeries) {
            if (s.priceChangeDate() != null) {
                if (truePrice.contains(s.accountId() + "|" + s.merchantId())) ptp++;
                else pfp++;
            }
        }
        out.put("price_change_detection", prf(ptp, pfp, truePrice.size() - ptp));
        return out;
    }

    // ------------------------------------------------------------------ timezone
    private Map<String, Object> timezone(Map<String, TransactionLabel> labels, Map<String, Classification> cls,
                                         Dataset d) {
        Map<String, Transaction> txns = new HashMap<>();
        d.transactions.forEach(t -> txns.put(t.transactionId(), t));
        int boundary = 0, engineOk = 0, naiveOk = 0;
        for (TransactionLabel l : labels.values()) {
            if (l.issueType() != null && l.issueType().contains("TIMEZONE_BOUNDARY")) {
                boundary++;
                if (l.budgetMonth().equals(cls.get(l.transactionId()).budgetMonth())) {
                    engineOk++;
                }
                String utcMonth = YearMonth.from(txns.get(l.transactionId()).postedAtUtc().atZone(ZoneOffset.UTC)).toString();
                if (l.budgetMonth().equals(utcMonth)) {
                    naiveOk++;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("boundary_transactions", boundary);
        out.put("engine_correct_budget_month", engineOk);
        out.put("naive_utc_correct_budget_month", naiveOk);
        out.put("engine_accuracy", round(ratio(engineOk, boundary)));
        return out;
    }

    // ------------------------------------------------------------------ budgets
    private Map<String, Object> budgetTaxonomy(GroundTruth g, EngineResult r) {
        Map<String, EngineResult.BudgetComparison> byId = new HashMap<>();
        r.budgetComparisons.forEach(b -> byId.put(b.budgetId(), b));
        int mismatch = 0, handled = 0, mapped = 0, flagged = 0;
        Map<String, Integer> resolutions = new TreeMap<>();
        for (BudgetLabel l : g.budgetLabels) {
            EngineResult.BudgetComparison b = byId.get(l.budgetId());
            resolutions.merge(b.resolution(), 1, Integer::sum);
            if (!"BUDGET_TAXONOMY_MISMATCH".equals(l.issueType())) {
                continue;
            }
            mismatch++;
            boolean ok = "UNMAPPED".equals(l.expectedCategoryCode()) ? b.resolvedCode() == null
                    : l.expectedCategoryCode().equals(b.resolvedCode());
            if (ok) {
                handled++;
                if (b.resolvedCode() == null) flagged++;
                else mapped++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("budget_rows", g.budgetLabels.size());
        out.put("resolution_counts", resolutions);
        out.put("mismatch_rows", mismatch);
        out.put("mismatch_handled_correctly", handled);
        out.put("mapped_via_alias_or_normalization", mapped);
        out.put("flagged_unmapped", flagged);
        out.put("accuracy", round(ratio(handled, mismatch)));
        return out;
    }

    // ------------------------------------------------------------------ actuals (engine vs naive)
    private Map<String, Object> actuals(Dataset d, GroundTruth g, EngineResult r) {
        Map<String, Long> truth = new HashMap<>();
        g.monthlyActuals.forEach(a -> truth.put(a.userId() + "|" + a.month() + "|" + a.categoryCode(), a.spendCents()));
        Map<String, Long> naive = naiveActuals(d);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cells_in_truth", truth.size());
        out.put("engine", compareCells(truth, r.monthlyActuals));
        out.put("naive_baseline", compareCells(truth, naive));
        long truthTotal = truth.values().stream().mapToLong(Long::longValue).sum();
        long engineTotal = r.monthlyActuals.values().stream().mapToLong(Long::longValue).sum();
        long naiveTotal = naive.values().stream().mapToLong(Long::longValue).sum();
        out.put("total_spend_truth", truthTotal / 100.0);
        out.put("total_spend_engine", engineTotal / 100.0);
        out.put("total_spend_naive", naiveTotal / 100.0);
        out.put("naive_overstatement_pct", round(ratio(naiveTotal - truthTotal, truthTotal) * 100));
        return out;
    }

    /** Naive: every outflow is spend (incl. transfers/card payments), positive CARD_PURCHASE rows are spend, UTC month. */
    private Map<String, Long> naiveActuals(Dataset d) {
        Map<String, String> userOf = new HashMap<>();
        d.accounts.forEach(a -> userOf.put(a.accountId(), a.userId()));
        RuleCategorizer cat = new RuleCategorizer(d.categoryRules);
        Map<String, Long> out = new HashMap<>();
        for (Transaction t : d.transactions) {
            LocalDate utcDate = t.postedAtUtc().atZone(ZoneOffset.UTC).toLocalDate();
            boolean spend = t.amountCents() < 0 && !"INTEREST".equals(t.txnType())
                    || t.amountCents() > 0 && "CARD_PURCHASE".equals(t.txnType());
            if (!spend) {
                continue;
            }
            String code = cat.categorize(t, utcDate).categoryCode();
            out.merge(userOf.get(t.accountId()) + "|" + YearMonth.from(utcDate) + "|" + code, Math.abs(t.amountCents()),
                    Long::sum);
        }
        return out;
    }

    private Map<String, Object> compareCells(Map<String, Long> truth, Map<String, Long> pred) {
        Set<String> keys = new HashSet<>(truth.keySet());
        keys.addAll(pred.keySet());
        double absErr = 0;
        int exact = 0;
        for (String k : keys) {
            long diff = Math.abs(truth.getOrDefault(k, 0L) - pred.getOrDefault(k, 0L));
            absErr += diff;
            if (diff <= 100) {
                exact++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cells_compared", keys.size());
        out.put("mae_dollars", round(absErr / Math.max(1, keys.size()) / 100.0));
        out.put("cells_within_1_dollar_pct", round(ratio(exact, keys.size()) * 100));
        return out;
    }

    // ------------------------------------------------------------------ insights
    private Map<String, Object> insights(GroundTruth g, EngineResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String type : List.of("OVER_BUDGET", "NEAR_BUDGET", "MISSED_RECURRING", "RECURRING_PRICE_CHANGE")) {
            Set<String> t = new HashSet<>();
            Set<String> p = new HashSet<>();
            g.expectedInsights.stream().filter(i -> i.insightType().equals(type)).forEach(i -> t.add(insightKey(i)));
            r.insights.stream().filter(i -> i.insightType().equals(type)).forEach(i -> p.add(insightKey(i)));
            int tp = 0;
            for (String k : p) {
                if (t.contains(k)) tp++;
            }
            Map<String, Object> m = prf(tp, p.size() - tp, t.size() - tp);
            m.put("expected", t.size());
            m.put("produced", p.size());
            out.put(type, m);
        }
        out.put("SPENDING_SPIKE_produced", r.insights.stream().filter(i -> i.insightType().equals("SPENDING_SPIKE")).count());
        return out;
    }

    private String insightKey(Insight i) {
        return i.userId() + "|" + i.month() + "|" + i.insightType() + "|" + (i.categoryCode() == null ? "" : i.categoryCode());
    }

    // ------------------------------------------------------------------ edge cases
    private Map<String, Object> edgeCases(Map<String, TransactionLabel> labels, Map<String, Classification> cls,
                                          GroundTruth g, EngineResult r) {
        Map<String, int[]> perIssue = new TreeMap<>();
        for (TransactionLabel l : labels.values()) {
            if ("NONE".equals(l.issueType())) {
                continue;
            }
            Classification c = cls.get(l.transactionId());
            for (String issue : l.issueType().split("\\|")) {
                boolean ok = switch (issue) {
                    case "TRANSFER_DOUBLE_COUNT" -> c.transfer() && c.spendImpactCents() == 0;
                    case "REFUND_AS_NEW_EXPENSE" -> c.refund() && c.spendImpactCents() < 0
                            && Objects.equals(c.refundOfTransactionId(), l.refundOfTransactionId());
                    case "TIMEZONE_BOUNDARY" -> l.budgetMonth().equals(c.budgetMonth());
                    case "RECURRING_FALSE_SKIP" -> c.recurringSeriesId() != null;
                    default -> false;
                };
                int[] v = perIssue.computeIfAbsent(issue, k -> new int[2]);
                v[0]++;
                if (ok) v[1]++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        perIssue.forEach((k, v) -> out.put(k, Map.of("labelled", v[0], "handled", v[1], "rate", round(ratio(v[1], v[0])))));
        // Series-level labels
        Map<String, DetectedSeries> det = new HashMap<>();
        r.recurringSeries.forEach(s -> det.putIfAbsent(s.accountId() + "|" + s.merchantId(), s));
        int skip = 0, skipOk = 0;
        for (RecurringSeriesTruth t : g.recurringSeries) {
            if ("RECURRING_TRUE_SKIP".equals(t.issueType())) {
                skip++;
                DetectedSeries s = det.get(t.accountId() + "|" + t.merchantId());
                if (s != null) {
                    Set<String> pm = new HashSet<>();
                    s.missedDates().forEach(m -> pm.add(YearMonth.from(m).toString()));
                    boolean all = Arrays.stream(t.missedDates().split(";"))
                            .allMatch(m -> pm.contains(YearMonth.from(LocalDate.parse(m)).toString()));
                    if (all) skipOk++;
                }
            }
        }
        out.put("RECURRING_TRUE_SKIP", Map.of("labelled", skip, "handled", skipOk, "rate", round(ratio(skipOk, skip))));
        int bm = 0, bmOk = 0;
        Map<String, EngineResult.BudgetComparison> byId = new HashMap<>();
        r.budgetComparisons.forEach(b -> byId.put(b.budgetId(), b));
        for (BudgetLabel l : g.budgetLabels) {
            if ("BUDGET_TAXONOMY_MISMATCH".equals(l.issueType())) {
                bm++;
                EngineResult.BudgetComparison b = byId.get(l.budgetId());
                boolean ok = "UNMAPPED".equals(l.expectedCategoryCode()) ? b.resolvedCode() == null
                        : l.expectedCategoryCode().equals(b.resolvedCode());
                if (ok) bmOk++;
            }
        }
        out.put("BUDGET_TAXONOMY_MISMATCH", Map.of("labelled", bm, "handled", bmOk, "rate", round(ratio(bmOk, bm))));
        return out;
    }

    private Map<String, Integer> exceptionCounts(EngineResult r) {
        Map<String, Integer> out = new TreeMap<>();
        r.exceptions.forEach(e -> out.merge(e.exceptionType(), 1, Integer::sum));
        return out;
    }

    // ------------------------------------------------------------------ helpers
    static Map<String, Object> prf(int tp, int fp, int fn) {
        double p = ratio(tp, tp + fp);
        double r = ratio(tp, tp + fn);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tp", tp);
        m.put("fp", fp);
        m.put("fn", fn);
        m.put("precision", round(p));
        m.put("recall", round(r));
        m.put("f1", round(p + r == 0 ? 0 : 2 * p * r / (p + r)));
        return m;
    }

    static double ratio(double a, double b) {
        return b == 0 ? 0 : a / b;
    }

    static double round(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }
}
