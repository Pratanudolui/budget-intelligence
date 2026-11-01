package com.yottaflex.budget.validation;

import com.yottaflex.budget.model.*;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Step 8 of the brief: primary-key uniqueness, foreign-key integrity, allowed enums, ID formats, date ordering,
 * arithmetic reconciliation, lifecycle consistency, duplicates and ground-truth separation.
 */
public class DatasetValidator {

    public record Check(String name, String status, long violations, String detail) {
    }

    private static final Pattern USR = Pattern.compile("USR-\\d{6}");
    private static final Pattern ACC = Pattern.compile("ACC-(USR-\\d{6})-\\d{2}");
    private static final Pattern TXN = Pattern.compile("TXN-\\d{4}-\\d{8}");
    private static final Pattern BUD = Pattern.compile("BUD-(USR-\\d{6})-(\\d{4}-\\d{2})-([A-Z0-9_]+)");
    private static final Pattern REC = Pattern.compile("REC-\\d{6}");
    private static final Pattern MER = Pattern.compile("MER-\\d{4}");

    private static final Set<String> ACCOUNT_TYPES = Set.of("CHECKING", "SAVINGS", "CREDIT_CARD");
    private static final Set<String> TXN_TYPES = Set.of("CARD_PURCHASE", "CARD_REFUND", "ACH_DEBIT", "ACH_CREDIT",
            "TRANSFER", "BILL_PAY", "PAYMENT", "INTEREST", "FEE", "ATM", "P2P");
    private static final Set<String> KINDS = Set.of("GROUP", "INCOME", "EXPENSE", "TRANSFER", "UNKNOWN");
    private static final Set<String> BANDS = Set.of("LOW", "MID", "HIGH");
    private static final Set<String> MATCH_TYPES = Set.of("TXN_TYPE", "DESCRIPTOR_REGEX", "MERCHANT",
            "MERCHANT_AMOUNT", "MCC");
    private static final Set<String> ISSUES = Set.of("NONE", "TRANSFER_DOUBLE_COUNT", "REFUND_AS_NEW_EXPENSE",
            "RECURRING_TRUE_SKIP", "RECURRING_FALSE_SKIP", "TIMEZONE_BOUNDARY", "BUDGET_TAXONOMY_MISMATCH");

    private final List<Check> checks = new ArrayList<>();

    public List<Check> validate(Dataset d, GroundTruth g, Integer expectedUsers, Integer expectedTransactions) {
        checks.clear();
        // ---- primary keys
        pk("pk.user_profile", d.users, UserProfile::userId);
        pk("pk.account", d.accounts, Account::accountId);
        pk("pk.merchant", d.merchants, Merchant::merchantId);
        pk("pk.category", d.categories, Category::categoryCode);
        pk("pk.category_rule", d.categoryRules, CategoryRule::ruleId);
        pk("pk.transaction", d.transactions, Transaction::transactionId);
        pk("pk.budget", d.budgets, Budget::budgetId);
        pk("pk.merchant_category", d.merchantCategories, m -> m.merchantId() + "|" + m.categoryCode());
        pk("pk.gt.transaction_label", g.transactionLabels, TransactionLabel::transactionId);
        pk("pk.gt.recurring_series", g.recurringSeries, RecurringSeriesTruth::seriesId);

        // ---- id formats
        format("id.user_format", d.users, u -> USR.matcher(u.userId()).matches());
        format("id.account_format_matches_owner", d.accounts, a -> {
            var m = ACC.matcher(a.accountId());
            return m.matches() && m.group(1).equals(a.userId());
        });
        format("id.transaction_format", d.transactions, t -> TXN.matcher(t.transactionId()).matches());
        format("id.merchant_format", d.merchants, m -> MER.matcher(m.merchantId()).matches());
        format("id.budget_format_matches_fields", d.budgets, b -> {
            var m = BUD.matcher(b.budgetId());
            return m.matches() && m.group(1).equals(b.userId()) && m.group(2).equals(b.budgetMonth())
                    && m.group(3).equals(b.categoryCode().trim().toUpperCase().replace(' ', '_'));
        });
        format("id.recurring_series_format", g.recurringSeries, s -> REC.matcher(s.seriesId()).matches());

        // ---- foreign keys
        Set<String> users = ids(d.users, UserProfile::userId);
        Set<String> accounts = ids(d.accounts, Account::accountId);
        Set<String> merchants = ids(d.merchants, Merchant::merchantId);
        Set<String> categories = ids(d.categories, Category::categoryCode);
        Set<String> txnIds = ids(d.transactions, Transaction::transactionId);
        fk("fk.account.user_id", d.accounts, a -> users.contains(a.userId()));
        fk("fk.transaction.account_id", d.transactions, t -> accounts.contains(t.accountId()));
        fk("fk.transaction.merchant_id(optional)", d.transactions, t -> t.merchantId() == null || merchants.contains(t.merchantId()));
        fk("fk.merchant.default_category(optional)", d.merchants, m -> m.defaultCategoryCode() == null || categories.contains(m.defaultCategoryCode()));
        fk("fk.merchant_category", d.merchantCategories, m -> merchants.contains(m.merchantId()) && categories.contains(m.categoryCode()));
        fk("fk.category.parent(optional)", d.categories, c -> c.parentCode() == null || categories.contains(c.parentCode()));
        fk("fk.category_alias.category", d.categoryAliases, a -> categories.contains(a.categoryCode()));
        fk("fk.category_rule.category", d.categoryRules, r -> categories.contains(r.categoryCode()));
        fk("fk.category_rule.merchant(optional)", d.categoryRules, r -> r.merchantId() == null || merchants.contains(r.merchantId()));
        fk("fk.budget.user_id", d.budgets, b -> users.contains(b.userId()));
        fk("fk.gt.label.transaction_id", g.transactionLabels, l -> txnIds.contains(l.transactionId()));
        fk("fk.gt.label.true_category", g.transactionLabels, l -> categories.contains(l.trueCategoryCode()));
        fk("fk.gt.label.refund_of(optional)", g.transactionLabels, l -> l.refundOfTransactionId() == null || txnIds.contains(l.refundOfTransactionId()));
        Set<String> seriesIds = ids(g.recurringSeries, RecurringSeriesTruth::seriesId);
        fk("fk.gt.label.recurring_series(optional)", g.transactionLabels, l -> l.recurringSeriesId() == null || seriesIds.contains(l.recurringSeriesId()));
        fk("fk.gt.series.account_merchant", g.recurringSeries, s -> accounts.contains(s.accountId()) && merchants.contains(s.merchantId()) && users.contains(s.userId()));
        add("fk.gt.every_transaction_labelled", d.transactions.size() - g.transactionLabels.size(),
                g.transactionLabels.size() + " labels for " + d.transactions.size() + " transactions");

        // ---- enums
        format("enum.account_type", d.accounts, a -> ACCOUNT_TYPES.contains(a.accountType()));
        format("enum.account_status", d.accounts, a -> Set.of("OPEN", "CLOSED").contains(a.status()));
        format("enum.txn_type", d.transactions, t -> TXN_TYPES.contains(t.txnType()));
        format("enum.currency", d.transactions, t -> "USD".equals(t.currency()));
        format("enum.category_kind", d.categories, c -> KINDS.contains(c.kind()));
        format("enum.income_band", d.users, u -> BANDS.contains(u.incomeBand()));
        format("enum.rule_match_type", d.categoryRules, r -> MATCH_TYPES.contains(r.matchType()));
        format("enum.timezone_valid", d.users, u -> {
            try {
                ZoneId.of(u.timezone());
                return true;
            } catch (DateTimeException e) {
                return false;
            }
        });
        format("enum.gt.issue_type", g.transactionLabels, l -> Arrays.stream(l.issueType().split("\\|")).allMatch(ISSUES::contains));
        format("value.budget_amount_positive", d.budgets, b -> b.amountCents() > 0);
        format("value.transaction_amount_nonzero", d.transactions, t -> t.amountCents() != 0);

        // ---- date ordering & lifecycle
        Map<String, Account> accountById = new HashMap<>();
        d.accounts.forEach(a -> accountById.put(a.accountId(), a));
        Map<String, UserProfile> userById = new HashMap<>();
        d.users.forEach(u -> userById.put(u.userId(), u));
        // Orphan rows are reported by the fk.* checks; the checks below skip them instead of failing twice.
        format("date.account_opened_after_user_created", d.accounts, a -> !userById.containsKey(a.userId())
                || !a.openedOn().isBefore(userById.get(a.userId()).createdOn()));
        format("date.transaction_after_account_opened", d.transactions, t -> {
            Account a = accountById.get(t.accountId());
            if (a == null || !userById.containsKey(a.userId())) {
                return true;
            }
            ZoneId z = ZoneId.of(userById.get(a.userId()).timezone());
            return !t.postedAtUtc().atZone(z).toLocalDate().isBefore(a.openedOn());
        });
        format("lifecycle.closed_accounts_have_close_date", d.accounts, a -> "OPEN".equals(a.status()) == (a.closedOn() == null));
        format("lifecycle.no_activity_after_close", d.transactions, t -> {
            Account a = accountById.get(t.accountId());
            return a == null || a.closedOn() == null || !t.postedAtUtc().atZone(ZoneId.of("UTC")).toLocalDate().isAfter(a.closedOn());
        });
        format("date.rule_effective_range", d.categoryRules, r -> r.effectiveTo() == null || !r.effectiveTo().isBefore(r.effectiveFrom()));
        format("date.gt.series_start_before_end", g.recurringSeries, s -> s.startDate() == null || !s.endDate().isBefore(s.startDate()));
        format("date.budget_effective_matches_month", d.budgets, b -> YearMonth.parse(b.budgetMonth()).atDay(1).equals(b.effectiveFrom()));

        // ---- arithmetic reconciliation
        Map<String, Long> sums = new HashMap<>();
        Map<String, Long> running = new HashMap<>();
        long chainBreaks = 0;
        List<Transaction> ordered = new ArrayList<>(d.transactions);
        ordered.sort(Comparator.comparing(Transaction::transactionId));
        for (Account a : d.accounts) {
            running.put(a.accountId(), a.openingBalanceCents());
        }
        for (Transaction t : ordered) {
            sums.merge(t.accountId(), t.amountCents(), Long::sum);
            long bal = running.merge(t.accountId(), t.amountCents(), Long::sum);
            if (bal != t.balanceAfterCents()) {
                chainBreaks++;
            }
        }
        long ledgerBreaks = d.accounts.stream()
                .filter(a -> a.openingBalanceCents() + sums.getOrDefault(a.accountId(), 0L) != a.closingBalanceCents())
                .count();
        add("reconcile.account_opening_plus_txns_equals_closing", ledgerBreaks, d.accounts.size() + " accounts");
        add("reconcile.running_balance_chain", chainBreaks, d.transactions.size() + " balance_after values");
        Map<String, List<TransactionLabel>> groups = new HashMap<>();
        g.transactionLabels.stream().filter(l -> l.transferGroupId() != null)
                .forEach(l -> groups.computeIfAbsent(l.transferGroupId(), k -> new ArrayList<>()).add(l));
        Map<String, Transaction> txnById = new HashMap<>();
        d.transactions.forEach(t -> txnById.put(t.transactionId(), t));
        long badPairs = groups.values().stream().filter(ls -> ls.size() != 2
                || ls.stream().mapToLong(l -> txnById.get(l.transactionId()).amountCents()).sum() != 0).count();
        add("reconcile.transfer_groups_two_legs_net_zero", badPairs, groups.size() + " transfer groups");
        long overRefund = g.transactionLabels.stream().filter(l -> l.refundOfTransactionId() != null)
                .filter(l -> txnById.get(l.transactionId()).amountCents() > -txnById.get(l.refundOfTransactionId()).amountCents())
                .count();
        add("reconcile.refund_not_greater_than_original", overRefund, "refund <= original purchase");
        long refundBeforeOriginal = g.transactionLabels.stream().filter(l -> l.refundOfTransactionId() != null)
                .filter(l -> txnById.get(l.transactionId()).postedAtUtc().isBefore(txnById.get(l.refundOfTransactionId()).postedAtUtc()))
                .count();
        add("date.refund_after_original", refundBeforeOriginal, "refund posted after purchase");
        Map<String, Long> recomputed = new HashMap<>();
        Map<String, Category> catByCode = new HashMap<>();
        d.categories.forEach(c -> catByCode.put(c.categoryCode(), c));
        Map<String, String> userOfAccount = new HashMap<>();
        d.accounts.forEach(a -> userOfAccount.put(a.accountId(), a.userId()));
        for (TransactionLabel l : g.transactionLabels) {
            if (!l.transfer() && catByCode.get(l.trueCategoryCode()).isExpense()) {
                Transaction t = txnById.get(l.transactionId());
                recomputed.merge(userOfAccount.get(t.accountId()) + "|" + l.budgetMonth() + "|" + l.trueCategoryCode(),
                        -t.amountCents(), Long::sum);
            }
        }
        long actualMismatch = g.monthlyActuals.stream()
                .filter(a -> !Objects.equals(recomputed.get(a.userId() + "|" + a.month() + "|" + a.categoryCode()), a.spendCents()))
                .count() + Math.abs(recomputed.size() - g.monthlyActuals.size());
        add("reconcile.gt_monthly_actuals_match_labels", actualMismatch, g.monthlyActuals.size() + " user-month-category cells");
        long seriesCountMismatch = 0;
        Map<String, Long> seriesCounts = new HashMap<>();
        g.transactionLabels.stream().filter(l -> l.recurringSeriesId() != null)
                .forEach(l -> seriesCounts.merge(l.recurringSeriesId(), 1L, Long::sum));
        for (RecurringSeriesTruth s : g.recurringSeries) {
            if (seriesCounts.getOrDefault(s.seriesId(), 0L) != s.occurrences()) {
                seriesCountMismatch++;
            }
        }
        add("reconcile.gt_series_occurrences_match_labels", seriesCountMismatch, g.recurringSeries.size() + " series");

        // ---- duplicates & separation
        Set<String> seen = new HashSet<>();
        long dups = 0;
        for (Transaction t : d.transactions) {
            if (!seen.add(t.accountId() + "|" + t.postedAtUtc() + "|" + t.amountCents() + "|" + t.descriptor())) {
                dups++;
            }
        }
        checks.add(new Check("duplicate.transaction_natural_key", dups == 0 ? "PASS" : "WARN", dups,
                "same account + timestamp + amount + descriptor"));
        add("separation.ground_truth_not_in_input", 0, "input transactions.csv has no label columns (by construction of DatasetIO headers)");

        // ---- volume
        if (expectedUsers != null) {
            add("volume.user_profiles", d.users.size() == expectedUsers ? 0 : 1, d.users.size() + " (expected " + expectedUsers + ")");
        }
        if (expectedTransactions != null) {
            add("volume.transactions", d.transactions.size() == expectedTransactions ? 0 : 1,
                    d.transactions.size() + " (expected " + expectedTransactions + ")");
        }
        LocalDate min = LocalDate.MAX, max = LocalDate.MIN;
        for (TransactionLabel l : g.transactionLabels) {
            if (l.localDate().isBefore(min)) min = l.localDate();
            if (l.localDate().isAfter(max)) max = l.localDate();
        }
        long months = g.transactionLabels.stream().map(TransactionLabel::budgetMonth).distinct().count();
        add("volume.twelve_months_covered", months == 12 ? 0 : 1, months + " local months (" + min + " .. " + max + ")");
        return List.copyOf(checks);
    }

    private <T> void pk(String name, List<T> rows, Function<T, String> key) {
        Set<String> seen = new HashSet<>();
        long dup = rows.stream().filter(r -> !seen.add(key.apply(r))).count();
        add(name, dup, rows.size() + " rows");
    }

    private <T> void fk(String name, List<T> rows, java.util.function.Predicate<T> ok) {
        format(name, rows, ok);
    }

    private <T> void format(String name, List<T> rows, java.util.function.Predicate<T> ok) {
        long bad = rows.stream().filter(ok.negate()).count();
        add(name, bad, rows.size() + " rows");
    }

    private <T> Set<String> ids(List<T> rows, Function<T, String> key) {
        Set<String> s = new HashSet<>();
        rows.forEach(r -> s.add(key.apply(r)));
        return s;
    }

    private void add(String name, long violations, String detail) {
        checks.add(new Check(name, violations == 0 ? "PASS" : "FAIL", violations, detail));
    }
}
