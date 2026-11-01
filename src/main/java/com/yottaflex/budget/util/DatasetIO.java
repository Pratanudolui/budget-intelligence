package com.yottaflex.budget.util;

import com.yottaflex.budget.model.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Reads and writes the on-disk dataset layout:
 * <pre>
 *   &lt;dir&gt;/input/*.csv          engine-visible data (seeded into PostgreSQL)
 *   &lt;dir&gt;/ground_truth/*.csv   labels for evaluation only
 * </pre>
 */
public final class DatasetIO {

    public static final String USERS = "user_profiles.csv";
    public static final String ACCOUNTS = "accounts.csv";
    public static final String MERCHANTS = "merchants.csv";
    public static final String MERCHANT_CATEGORIES = "merchant_categories.csv";
    public static final String CATEGORIES = "categories.csv";
    public static final String CATEGORY_ALIASES = "category_aliases.csv";
    public static final String CATEGORY_RULES = "category_rules.csv";
    public static final String TRANSACTIONS = "transactions.csv";
    public static final String BUDGETS = "budgets.csv";

    public static final String GT_TXN = "transaction_labels.csv";
    public static final String GT_RECURRING = "recurring_series.csv";
    public static final String GT_BUDGET = "budget_labels.csv";
    public static final String GT_ACTUALS = "monthly_actuals.csv";
    public static final String GT_INSIGHTS = "insights.csv";

    public static final String H_USERS = "user_id,display_name,household_size,timezone,income_band,annual_income,city,created_on,status";
    public static final String H_ACCOUNTS = "account_id,user_id,account_type,opened_on,closed_on,status,currency,opening_balance,closing_balance";
    public static final String H_MERCHANTS = "merchant_id,canonical_name,merchant_type,default_category_code,mcc,status";
    public static final String H_MERCHANT_CATEGORIES = "merchant_id,category_code,is_primary";
    public static final String H_CATEGORIES = "category_code,name,parent_code,kind,essential,effective_from,status";
    public static final String H_CATEGORY_ALIASES = "alias_code,category_code";
    public static final String H_CATEGORY_RULES = "rule_id,priority,match_type,merchant_id,pattern,mcc,txn_type,min_abs_amount,max_abs_amount,category_code,effective_from,effective_to,status";
    public static final String H_TRANSACTIONS = "transaction_id,account_id,merchant_id,posted_at_utc,amount,currency,descriptor,txn_type,mcc,balance_after";
    public static final String H_BUDGETS = "budget_id,user_id,budget_month,category_code,budget_amount,currency,effective_from,status";
    public static final String H_GT_TXN = "transaction_id,true_category_code,is_transfer,transfer_group_id,is_refund,refund_of_transaction_id,recurring_series_id,local_date,budget_month,issue_type,expected_decision";
    public static final String H_GT_RECURRING = "series_id,user_id,account_id,merchant_id,kind,cadence,expected_amount,start_date,end_date,status,occurrences,missed_dates,shifted_dates,price_change_date,issue_type";
    public static final String H_GT_BUDGET = "budget_id,supplied_category_code,expected_category_code,issue_type,expected_decision";
    public static final String H_GT_ACTUALS = "user_id,month,category_code,spend_amount,txn_count";
    public static final String H_GT_INSIGHTS = "insight_id,user_id,month,insight_type,category_code,actual_amount,reference_amount,severity,evidence";

    private DatasetIO() {
    }

    public static void writeInput(Dataset d, Path dir) {
        Path in = dir.resolve("input");
        Csv.write(in.resolve(USERS), H_USERS, d.users, u -> new Object[]{u.userId(), u.displayName(), u.householdSize(),
                u.timezone(), u.incomeBand(), Money.format(u.annualIncomeCents()), u.city(), u.createdOn(), u.status()});
        Csv.write(in.resolve(ACCOUNTS), H_ACCOUNTS, d.accounts, a -> new Object[]{a.accountId(), a.userId(), a.accountType(),
                a.openedOn(), a.closedOn(), a.status(), a.currency(), Money.format(a.openingBalanceCents()),
                Money.format(a.closingBalanceCents())});
        Csv.write(in.resolve(MERCHANTS), H_MERCHANTS, d.merchants, m -> new Object[]{m.merchantId(), m.canonicalName(),
                m.merchantType(), m.defaultCategoryCode(), m.mcc(), m.status()});
        Csv.write(in.resolve(MERCHANT_CATEGORIES), H_MERCHANT_CATEGORIES, d.merchantCategories,
                m -> new Object[]{m.merchantId(), m.categoryCode(), m.primary()});
        Csv.write(in.resolve(CATEGORIES), H_CATEGORIES, d.categories, c -> new Object[]{c.categoryCode(), c.name(),
                c.parentCode(), c.kind(), c.essential(), c.effectiveFrom(), c.status()});
        Csv.write(in.resolve(CATEGORY_ALIASES), H_CATEGORY_ALIASES, d.categoryAliases,
                a -> new Object[]{a.aliasCode(), a.categoryCode()});
        Csv.write(in.resolve(CATEGORY_RULES), H_CATEGORY_RULES, d.categoryRules, r -> new Object[]{r.ruleId(), r.priority(),
                r.matchType(), r.merchantId(), r.pattern(), r.mcc(), r.txnType(),
                r.minAbsCents() == null ? null : Money.format(r.minAbsCents()),
                r.maxAbsCents() == null ? null : Money.format(r.maxAbsCents()),
                r.categoryCode(), r.effectiveFrom(), r.effectiveTo(), r.status()});
        Csv.write(in.resolve(TRANSACTIONS), H_TRANSACTIONS, d.transactions, t -> new Object[]{t.transactionId(),
                t.accountId(), t.merchantId(), t.postedAtUtc(), Money.format(t.amountCents()), t.currency(),
                t.descriptor(), t.txnType(), t.mcc(), Money.format(t.balanceAfterCents())});
        Csv.write(in.resolve(BUDGETS), H_BUDGETS, d.budgets, b -> new Object[]{b.budgetId(), b.userId(), b.budgetMonth(),
                b.categoryCode(), Money.format(b.amountCents()), b.currency(), b.effectiveFrom(), b.status()});
    }

    public static void writeGroundTruth(GroundTruth g, Path dir) {
        Path gt = dir.resolve("ground_truth");
        Csv.write(gt.resolve(GT_TXN), H_GT_TXN, g.transactionLabels, l -> new Object[]{l.transactionId(),
                l.trueCategoryCode(), l.transfer(), l.transferGroupId(), l.refund(), l.refundOfTransactionId(),
                l.recurringSeriesId(), l.localDate(), l.budgetMonth(), l.issueType(), l.expectedDecision()});
        Csv.write(gt.resolve(GT_RECURRING), H_GT_RECURRING, g.recurringSeries, s -> new Object[]{s.seriesId(), s.userId(),
                s.accountId(), s.merchantId(), s.kind(), s.cadence(), Money.format(s.expectedAmountCents()), s.startDate(),
                s.endDate(), s.status(), s.occurrences(), s.missedDates(), s.shiftedDates(), s.priceChangeDate(),
                s.issueType()});
        Csv.write(gt.resolve(GT_BUDGET), H_GT_BUDGET, g.budgetLabels, b -> new Object[]{b.budgetId(),
                b.suppliedCategoryCode(), b.expectedCategoryCode(), b.issueType(), b.expectedDecision()});
        Csv.write(gt.resolve(GT_ACTUALS), H_GT_ACTUALS, g.monthlyActuals, a -> new Object[]{a.userId(), a.month(),
                a.categoryCode(), Money.format(a.spendCents()), a.txnCount()});
        Csv.write(gt.resolve(GT_INSIGHTS), H_GT_INSIGHTS, g.expectedInsights, i -> new Object[]{i.insightId(), i.userId(),
                i.month(), i.insightType(), i.categoryCode(), Money.format(i.actualCents()),
                Money.format(i.referenceCents()), i.severity(), i.evidence()});
    }

    public static Dataset readInput(Path dir) {
        Path in = dir.resolve("input");
        Dataset d = new Dataset();
        for (String[] r : Csv.read(in.resolve(USERS))) {
            d.users.add(new UserProfile(r[0], r[1], Integer.parseInt(r[2]), r[3], r[4], Money.parse(r[5]), r[6],
                    date(r[7]), r[8]));
        }
        for (String[] r : Csv.read(in.resolve(ACCOUNTS))) {
            d.accounts.add(new Account(r[0], r[1], r[2], date(r[3]), date(r[4]), r[5], r[6], Money.parse(r[7]),
                    Money.parse(r[8])));
        }
        for (String[] r : Csv.read(in.resolve(MERCHANTS))) {
            d.merchants.add(new Merchant(r[0], r[1], r[2], r[3], r[4], r[5]));
        }
        for (String[] r : Csv.read(in.resolve(MERCHANT_CATEGORIES))) {
            d.merchantCategories.add(new MerchantCategory(r[0], r[1], Boolean.parseBoolean(r[2])));
        }
        for (String[] r : Csv.read(in.resolve(CATEGORIES))) {
            d.categories.add(new Category(r[0], r[1], r[2], r[3], Boolean.parseBoolean(r[4]), date(r[5]), r[6]));
        }
        for (String[] r : Csv.read(in.resolve(CATEGORY_ALIASES))) {
            d.categoryAliases.add(new CategoryAlias(r[0], r[1]));
        }
        for (String[] r : Csv.read(in.resolve(CATEGORY_RULES))) {
            d.categoryRules.add(new CategoryRule(r[0], Integer.parseInt(r[1]), r[2], r[3], r[4], r[5], r[6],
                    r[7] == null ? null : Money.parse(r[7]), r[8] == null ? null : Money.parse(r[8]), r[9],
                    date(r[10]), date(r[11]), r[12]));
        }
        for (String[] r : Csv.read(in.resolve(TRANSACTIONS))) {
            d.transactions.add(new Transaction(r[0], r[1], r[2], Instant.parse(r[3]), Money.parse(r[4]), r[5], r[6],
                    r[7], r[8], Money.parse(r[9])));
        }
        for (String[] r : Csv.read(in.resolve(BUDGETS))) {
            d.budgets.add(new Budget(r[0], r[1], r[2], r[3], Money.parse(r[4]), r[5], date(r[6]), r[7]));
        }
        return d;
    }

    public static GroundTruth readGroundTruth(Path dir) {
        Path gt = dir.resolve("ground_truth");
        GroundTruth g = new GroundTruth();
        for (String[] r : Csv.read(gt.resolve(GT_TXN))) {
            g.transactionLabels.add(new TransactionLabel(r[0], r[1], Boolean.parseBoolean(r[2]), r[3],
                    Boolean.parseBoolean(r[4]), r[5], r[6], date(r[7]), r[8], r[9], r[10]));
        }
        for (String[] r : Csv.read(gt.resolve(GT_RECURRING))) {
            g.recurringSeries.add(new RecurringSeriesTruth(r[0], r[1], r[2], r[3], r[4], r[5], Money.parse(r[6]),
                    date(r[7]), date(r[8]), r[9], Integer.parseInt(r[10]), r[11], r[12], r[13], r[14]));
        }
        for (String[] r : Csv.read(gt.resolve(GT_BUDGET))) {
            g.budgetLabels.add(new BudgetLabel(r[0], r[1], r[2], r[3], r[4]));
        }
        for (String[] r : Csv.read(gt.resolve(GT_ACTUALS))) {
            g.monthlyActuals.add(new MonthlyActual(r[0], r[1], r[2], Money.parse(r[3]), Integer.parseInt(r[4])));
        }
        Path insights = gt.resolve(GT_INSIGHTS);
        if (Files.exists(insights)) {
            for (String[] r : Csv.read(insights)) {
                g.expectedInsights.add(new Insight(r[0], r[1], r[2], r[3], r[4], Money.parse(r[5]), Money.parse(r[6]),
                        r[7], r[8]));
            }
        }
        return g;
    }

    private static LocalDate date(String s) {
        return s == null ? null : LocalDate.parse(s);
    }

    public static List<String> inputFiles() {
        return List.of(USERS, ACCOUNTS, MERCHANTS, MERCHANT_CATEGORIES, CATEGORIES, CATEGORY_ALIASES, CATEGORY_RULES,
                TRANSACTIONS, BUDGETS);
    }

    public static List<String> groundTruthFiles() {
        return List.of(GT_TXN, GT_RECURRING, GT_BUDGET, GT_ACTUALS, GT_INSIGHTS);
    }
}
