package com.yottaflex.budget.engine;

import com.yottaflex.budget.engine.EngineResult.*;
import com.yottaflex.budget.engine.RecurringDetector.Posting;
import com.yottaflex.budget.model.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * The end-to-end decision pipeline. Pure Java over a {@link Dataset}; the Spring layer loads the dataset from
 * PostgreSQL and persists the {@link EngineResult}, and the CLI runs it straight from CSV.
 *
 * <ol>
 *   <li>Resolve each transaction's local date in the account holder's timezone (budget month = local month).</li>
 *   <li>Rule-based categorization (priority-ordered, effective-dated rules).</li>
 *   <li>Transfer pairing across a user's own accounts: paired legs are excluded from spending.</li>
 *   <li>Refund detection and linking: refunds inherit the original purchase's category and net against it.</li>
 *   <li>Recurring series detection with tolerance-window due-date matching.</li>
 *   <li>Monthly actuals, budget comparison with taxonomy resolution, insights and exceptions.</li>
 * </ol>
 */
public class BudgetIntelligenceEngine {

    public static final int TRANSFER_WINDOW_DAYS = 4;
    public static final int REFUND_WINDOW_DAYS = 120;
    public static final double SPIKE_RATIO = 1.75;
    public static final long SPIKE_MIN_CENTS = 20_000;

    private static final Set<String> TRANSFER_OUT_TYPES = Set.of("TRANSFER", "ACH_DEBIT", "BILL_PAY");
    private static final Set<String> TRANSFER_IN_TYPES = Set.of("TRANSFER", "ACH_CREDIT", "PAYMENT");
    private static final Set<String> NON_RECURRING_CATEGORIES = Set.of("INTR", "FEES", "CASH", "PEER");

    public EngineResult run(Dataset d) {
        EngineResult result = new EngineResult();
        Map<String, Account> accounts = new HashMap<>();
        d.accounts.forEach(a -> accounts.put(a.accountId(), a));
        Map<String, UserProfile> users = new HashMap<>();
        d.users.forEach(u -> users.put(u.userId(), u));
        Map<String, Category> categories = new HashMap<>();
        d.categories.forEach(c -> categories.put(c.categoryCode(), c));
        Map<String, Merchant> merchants = new HashMap<>();
        d.merchants.forEach(m -> merchants.put(m.merchantId(), m));

        List<Transaction> txns = new ArrayList<>(d.transactions);
        txns.sort(Comparator.comparing(Transaction::postedAtUtc).thenComparing(Transaction::transactionId));
        int n = txns.size();
        String[] userOf = new String[n];
        LocalDate[] local = new LocalDate[n];
        RuleCategorizer.Match[] base = new RuleCategorizer.Match[n];
        RuleCategorizer categorizer = new RuleCategorizer(d.categoryRules);
        Map<String, ZoneId> zones = new HashMap<>();
        Map<String, Integer> indexById = new HashMap<>();
        LocalDate dataEnd = LocalDate.MIN;
        for (int i = 0; i < n; i++) {
            Transaction t = txns.get(i);
            indexById.put(t.transactionId(), i);
            String userId = accounts.get(t.accountId()).userId();
            userOf[i] = userId;
            ZoneId zone = zones.computeIfAbsent(userId, u -> ZoneId.of(users.get(u).timezone()));
            local[i] = t.postedAtUtc().atZone(zone).toLocalDate();
            if (local[i].isAfter(dataEnd)) {
                dataEnd = local[i];
            }
            base[i] = categorizer.categorize(t, local[i]);
        }

        // ---- transfers
        String[] transferGroup = new String[n];
        String[] transferKind = new String[n];
        pairTransfers(txns, userOf, local, base, categories, merchants, accounts, transferGroup, transferKind, result);

        // ---- refunds
        int[] refundOf = new int[n];
        Arrays.fill(refundOf, -1);
        boolean[] isRefund = new boolean[n];
        linkRefunds(txns, userOf, local, base, categories, transferGroup, refundOf, isRefund, result);

        // ---- final classification (recurring ids filled after detection)
        String[] finalCategory = new String[n];
        String[] method = new String[n];
        String[] reason = new String[n];
        for (int i = 0; i < n; i++) {
            Transaction t = txns.get(i);
            if (transferGroup[i] != null) {
                finalCategory[i] = transferKind[i];
                method[i] = "TRANSFER_PAIR";
                reason[i] = "paired internal transfer " + transferGroup[i] + "; excluded from spending";
            } else if (isRefund[i] && refundOf[i] >= 0) {
                finalCategory[i] = finalCategoryOfOriginal(refundOf[i], base);
                method[i] = "REFUND_LINK";
                reason[i] = "refund of " + txns.get(refundOf[i]).transactionId() + "; nets against original category";
            } else if (isRefund[i]) {
                finalCategory[i] = base[i].categoryCode();
                method[i] = "REFUND_UNLINKED";
                reason[i] = "credit at expense merchant without matching purchase";
            } else if (base[i].ruleId() == null) {
                finalCategory[i] = RuleCategorizer.FALLBACK_CATEGORY;
                method[i] = "FALLBACK";
                reason[i] = "no rule matched";
                result.exceptions.add(exception("UNCATEGORIZED_TRANSACTION", "LOW", "TRANSACTION", t.transactionId(),
                        userOf[i], "No rule matched descriptor '" + t.descriptor() + "' mcc=" + t.mcc()));
            } else {
                finalCategory[i] = base[i].categoryCode();
                method[i] = "RULE_" + base[i].matchType();
                reason[i] = "matched " + base[i].ruleId();
            }
            if (transferGroup[i] == null && categories.get(base[i].categoryCode()) != null
                    && categories.get(base[i].categoryCode()).isTransfer()) {
                result.exceptions.add(exception("UNMATCHED_TRANSFER_LEG", "MEDIUM", "TRANSACTION", t.transactionId(),
                        userOf[i], "Transfer-like descriptor without a matching leg on another own account"));
            }
        }

        // ---- recurring detection
        List<Posting> postings = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Transaction t = txns.get(i);
            if (transferGroup[i] != null || isRefund[i] || NON_RECURRING_CATEGORIES.contains(finalCategory[i])) {
                continue;
            }
            Merchant m = t.merchantId() == null ? null : merchants.get(t.merchantId());
            postings.add(new Posting(t.transactionId(), userOf[i], t.accountId(), t.merchantId(),
                    m == null ? null : m.merchantType(), finalCategory[i], local[i], t.amountCents()));
        }
        List<DetectedSeries> series = new RecurringDetector().detect(postings, dataEnd);
        result.recurringSeries.addAll(series);
        Map<String, String> seriesOfTxn = new HashMap<>();
        for (DetectedSeries s : series) {
            s.memberTransactionIds().forEach(id -> seriesOfTxn.put(id, s.seriesId()));
            for (LocalDate missed : s.missedDates()) {
                result.exceptions.add(exception("MISSED_RECURRING_PAYMENT", "MEDIUM", "RECURRING_SERIES",
                        s.seriesId(), s.userId(), "Expected " + s.kind() + " payment around " + missed
                                + " (+/-" + RecurringDetector.MONTHLY_TOLERANCE_DAYS + "d) not found"));
            }
        }

        // ---- classifications + monthly actuals
        Map<String, int[]> counts = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Transaction t = txns.get(i);
            Category c = categories.get(finalCategory[i]);
            boolean spend = transferGroup[i] == null && c != null && c.isExpense();
            long impact = spend ? -t.amountCents() : 0;
            String month = YearMonth.from(local[i]).toString();
            if (spend) {
                String key = userOf[i] + "|" + month + "|" + finalCategory[i];
                result.monthlyActuals.merge(key, impact, Long::sum);
                counts.computeIfAbsent(key, k -> new int[1])[0]++;
            }
            result.classifications.add(new Classification(t.transactionId(), userOf[i], finalCategory[i], method[i],
                    base[i].ruleId(), transferGroup[i] != null ? 0.97 : base[i].confidence(), transferGroup[i] != null,
                    transferGroup[i], isRefund[i], refundOf[i] >= 0 ? txns.get(refundOf[i]).transactionId() : null,
                    seriesOfTxn.get(t.transactionId()), local[i], month, impact, reason[i]));
        }

        compareBudgets(d, categories, result);
        buildInsights(result);
        numberExceptions(result);
        return result;
    }

    private String finalCategoryOfOriginal(int original, RuleCategorizer.Match[] base) {
        return base[original].categoryCode();
    }

    private void pairTransfers(List<Transaction> txns, String[] userOf, LocalDate[] local, RuleCategorizer.Match[] base,
                               Map<String, Category> categories, Map<String, Merchant> merchants,
                               Map<String, Account> accounts, String[] group, String[] kind, EngineResult result) {
        // inflow candidates keyed by user|amount, in time order
        Map<String, Deque<Integer>> inflows = new HashMap<>();
        for (int i = 0; i < txns.size(); i++) {
            Transaction t = txns.get(i);
            if (t.amountCents() > 0 && TRANSFER_IN_TYPES.contains(t.txnType()) && !isIncome(base[i], categories)
                    && !isEmployer(t, merchants)) {
                inflows.computeIfAbsent(userOf[i] + "|" + t.amountCents(), k -> new ArrayDeque<>()).add(i);
            }
        }
        int seq = 0;
        boolean[] used = new boolean[txns.size()];
        for (int i = 0; i < txns.size(); i++) {
            Transaction t = txns.get(i);
            if (t.amountCents() >= 0 || !(TRANSFER_OUT_TYPES.contains(t.txnType()) || isTransferCategory(base[i], categories))) {
                continue;
            }
            Deque<Integer> q = inflows.get(userOf[i] + "|" + (-t.amountCents()));
            if (q == null) {
                continue;
            }
            Integer match = null;
            for (Integer j : q) {
                if (used[j] || txns.get(j).accountId().equals(t.accountId())) {
                    continue;
                }
                long lag = ChronoUnit.DAYS.between(local[i], local[j]);
                if (lag >= 0 && lag <= TRANSFER_WINDOW_DAYS) {
                    match = j;
                    break;
                }
            }
            if (match == null) {
                continue;
            }
            used[match] = true;
            q.remove(match);
            String id = String.format("TRF-%07d", ++seq);
            Transaction in = txns.get(match);
            String k = "CREDIT_CARD".equals(accounts.get(in.accountId()).accountType()) ? "CCPY" : "XFER";
            group[i] = id;
            group[match] = id;
            kind[i] = k;
            kind[match] = k;
            result.transferLinks.add(new TransferLink(id, t.transactionId(), in.transactionId(), k,
                    (int) ChronoUnit.DAYS.between(local[i], local[match]), -t.amountCents()));
        }
    }

    private void linkRefunds(List<Transaction> txns, String[] userOf, LocalDate[] local, RuleCategorizer.Match[] base,
                             Map<String, Category> categories, String[] transferGroup, int[] refundOf,
                             boolean[] isRefund, EngineResult result) {
        Map<String, List<Integer>> purchasesByKey = new HashMap<>();
        boolean[] consumed = new boolean[txns.size()];
        for (int i = 0; i < txns.size(); i++) {
            Transaction t = txns.get(i);
            if (transferGroup[i] != null) {
                continue;
            }
            Category c = categories.get(base[i].categoryCode());
            boolean expenseLike = c != null && (c.isExpense() || "UNKNOWN".equals(c.kind()));
            if (t.amountCents() < 0 && t.merchantId() != null) {
                purchasesByKey.computeIfAbsent(t.accountId() + "|" + t.merchantId(), k -> new ArrayList<>()).add(i);
                continue;
            }
            if (t.amountCents() <= 0 || !(expenseLike || "CARD_REFUND".equals(t.txnType()))) {
                continue;
            }
            isRefund[i] = true;
            List<Integer> candidates = t.merchantId() == null ? List.of()
                    : purchasesByKey.getOrDefault(t.accountId() + "|" + t.merchantId(), List.of());
            int best = -1;
            for (int k = candidates.size() - 1; k >= 0; k--) {
                int j = candidates.get(k);
                long orig = -txns.get(j).amountCents();
                long lag = ChronoUnit.DAYS.between(local[j], local[i]);
                if (lag > REFUND_WINDOW_DAYS) {
                    break;
                }
                if (consumed[j] || orig < t.amountCents()) {
                    continue;
                }
                if (orig == t.amountCents()) {
                    best = j;
                    break;
                }
                if (best < 0) {
                    best = j;
                }
            }
            if (best >= 0) {
                consumed[best] = true;
                refundOf[i] = best;
                result.refundLinks.add(new RefundLink(t.transactionId(), txns.get(best).transactionId(),
                        t.amountCents(), (int) ChronoUnit.DAYS.between(local[best], local[i])));
            } else {
                result.exceptions.add(exception("REFUND_WITHOUT_ORIGINAL", "LOW", "TRANSACTION", t.transactionId(),
                        userOf[i], "Credit of " + t.amountCents() / 100.0 + " with no matching purchase within "
                                + REFUND_WINDOW_DAYS + " days"));
            }
        }
    }

    private void compareBudgets(Dataset d, Map<String, Category> categories, EngineResult result) {
        Map<String, String> aliases = new HashMap<>();
        d.categoryAliases.forEach(a -> aliases.put(a.aliasCode(), a.categoryCode()));
        Map<String, List<String>> children = new HashMap<>();
        d.categories.forEach(c -> {
            if (c.parentCode() != null) {
                children.computeIfAbsent(c.parentCode(), k -> new ArrayList<>()).add(c.categoryCode());
            }
        });
        Map<String, Integer> unmapped = new TreeMap<>();
        for (Budget b : d.budgets) {
            String supplied = b.categoryCode();
            String normalized = supplied == null ? "" : supplied.trim().toUpperCase();
            String resolved;
            String resolution;
            if (categories.containsKey(supplied)) {
                resolved = supplied;
                resolution = "EXACT";
            } else if (categories.containsKey(normalized)) {
                resolved = normalized;
                resolution = "NORMALIZED";
            } else if (aliases.containsKey(normalized)) {
                resolved = aliases.get(normalized);
                resolution = "ALIAS";
            } else {
                resolved = null;
                resolution = "UNMAPPED";
            }
            if (resolved == null) {
                unmapped.merge(b.userId() + "|" + supplied, 1, Integer::sum);
                result.budgetComparisons.add(new BudgetComparison(b.budgetId(), b.userId(), b.budgetMonth(), supplied,
                        null, resolution, b.amountCents(), 0, 0, 0, "UNMAPPED"));
                continue;
            }
            long actual = 0;
            List<String> codes = "GROUP".equals(categories.get(resolved).kind())
                    ? children.getOrDefault(resolved, List.of()) : List.of(resolved);
            for (String code : codes) {
                actual += result.monthlyActuals.getOrDefault(b.userId() + "|" + b.budgetMonth() + "|" + code, 0L);
            }
            double utilization = b.amountCents() == 0 ? 0 : (double) actual / b.amountCents();
            String type = InsightRules.budgetInsightType(actual, b.amountCents());
            String status = type == null ? "UNDER" : "OVER_BUDGET".equals(type) ? "OVER" : "NEAR";
            result.budgetComparisons.add(new BudgetComparison(b.budgetId(), b.userId(), b.budgetMonth(), supplied,
                    resolved, resolution, b.amountCents(), actual, b.amountCents() - actual,
                    Math.round(utilization * 10_000) / 10_000.0, status));
        }
        for (Map.Entry<String, Integer> e : unmapped.entrySet()) {
            String[] k = e.getKey().split("\\|", 2);
            result.exceptions.add(exception("BUDGET_CATEGORY_UNMAPPED", "HIGH", "BUDGET", k[1], k[0],
                    "Budget category '" + k[1] + "' is not in the taxonomy or alias table (" + e.getValue()
                            + " monthly budget rows excluded from comparison)"));
        }
    }

    private void buildInsights(EngineResult result) {
        int seq = 0;
        for (BudgetComparison c : result.budgetComparisons) {
            if ("OVER".equals(c.status()) || "NEAR".equals(c.status())) {
                String type = "OVER".equals(c.status()) ? "OVER_BUDGET" : "NEAR_BUDGET";
                result.insights.add(new Insight(String.format("INS-%07d", ++seq), c.userId(), c.month(), type,
                        c.resolvedCode(), c.actualCents(), c.budgetCents(),
                        InsightRules.severity(type, c.actualCents(), c.budgetCents()),
                        "budget_id=" + c.budgetId() + ";utilization=" + c.utilization()));
            }
        }
        for (DetectedSeries s : result.recurringSeries) {
            if (s.priceChangeDate() != null) {
                result.insights.add(new Insight(String.format("INS-%07d", ++seq), s.userId(),
                        YearMonth.from(s.priceChangeDate()).toString(), "RECURRING_PRICE_CHANGE", null, 0,
                        s.typicalAmountCents(), "LOW", "series_id=" + s.seriesId()));
            }
            for (LocalDate m : s.missedDates()) {
                result.insights.add(new Insight(String.format("INS-%07d", ++seq), s.userId(),
                        YearMonth.from(m).toString(), "MISSED_RECURRING", null, 0, s.typicalAmountCents(), "MEDIUM",
                        "series_id=" + s.seriesId()));
            }
        }
        // Spending spikes: a month more than 1.75x the user's average for that category (informational).
        Map<String, long[]> perUserCategory = new HashMap<>();
        for (Map.Entry<String, Long> e : result.monthlyActuals.entrySet()) {
            String[] k = e.getKey().split("\\|");
            long[] v = perUserCategory.computeIfAbsent(k[0] + "|" + k[2], x -> new long[2]);
            v[0] += e.getValue();
            v[1]++;
        }
        for (Map.Entry<String, Long> e : result.monthlyActuals.entrySet()) {
            String[] k = e.getKey().split("\\|");
            long[] v = perUserCategory.get(k[0] + "|" + k[2]);
            long avg = v[1] == 0 ? 0 : v[0] / v[1];
            if (v[1] >= 3 && e.getValue() > SPIKE_MIN_CENTS && e.getValue() > avg * SPIKE_RATIO) {
                result.insights.add(new Insight(String.format("INS-%07d", ++seq), k[0], k[1], "SPENDING_SPIKE", k[2],
                        e.getValue(), avg, "LOW", "monthly_avg=" + avg));
            }
        }
    }

    private void numberExceptions(EngineResult result) {
        List<ExceptionItem> numbered = new ArrayList<>();
        int seq = 0;
        for (ExceptionItem e : result.exceptions) {
            numbered.add(new ExceptionItem(String.format("EXC-%07d", ++seq), e.exceptionType(), e.severity(),
                    e.entityType(), e.entityId(), e.userId(), e.detail()));
        }
        result.exceptions.clear();
        result.exceptions.addAll(numbered);
    }

    private static ExceptionItem exception(String type, String severity, String entityType, String entityId,
                                           String userId, String detail) {
        return new ExceptionItem(null, type, severity, entityType, entityId, userId, detail);
    }

    private static boolean isIncome(RuleCategorizer.Match m, Map<String, Category> categories) {
        Category c = categories.get(m.categoryCode());
        return c != null && "INCOME".equals(c.kind());
    }

    private static boolean isTransferCategory(RuleCategorizer.Match m, Map<String, Category> categories) {
        Category c = categories.get(m.categoryCode());
        return c != null && c.isTransfer();
    }

    private static boolean isEmployer(Transaction t, Map<String, Merchant> merchants) {
        Merchant m = t.merchantId() == null ? null : merchants.get(t.merchantId());
        return m != null && "EMPLOYER".equals(m.merchantType());
    }
}
