package com.yottaflex.budget.generator;

import com.yottaflex.budget.generator.Catalog.MerchantSpec;
import com.yottaflex.budget.engine.InsightRules;
import com.yottaflex.budget.model.*;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Deterministic synthetic data generator.
 *
 * <p>Generation order follows the brief: master data (taxonomy, merchants, rules, users, accounts) first,
 * then recurring "structural" ledger entries, then discretionary purchases/refunds, then derived entries
 * (card payments, interest), then labelled edge-case injection, and finally derived values (IDs, balances,
 * monthly actuals, budgets, expected insights) computed from the generated records.</p>
 *
 * <p>Every random draw comes from a {@link Random} seeded by (seed, stream, user index), so output is
 * byte-for-byte reproducible for a given seed and scale.</p>
 */
public class SyntheticDataGenerator {

    // Issue types (ground-truth labels for the required edge cases)
    public static final String ISSUE_TRANSFER = "TRANSFER_DOUBLE_COUNT";
    public static final String ISSUE_REFUND = "REFUND_AS_NEW_EXPENSE";
    public static final String ISSUE_TRUE_SKIP = "RECURRING_TRUE_SKIP";
    public static final String ISSUE_FALSE_SKIP = "RECURRING_FALSE_SKIP";
    public static final String ISSUE_TIMEZONE = "TIMEZONE_BOUNDARY";
    public static final String ISSUE_BUDGET = "BUDGET_TAXONOMY_MISMATCH";

    private static final String[] ZONES = {"America/New_York", "America/Chicago", "America/Denver",
            "America/Los_Angeles", "Asia/Kolkata", "Europe/London"};
    private static final int[] ZONE_WEIGHTS = {35, 25, 10, 22, 4, 4};
    private static final Map<String, String[]> CITIES = Map.of(
            "America/New_York", new String[]{"NEW YORK", "BOSTON", "ATLANTA", "MIAMI"},
            "America/Chicago", new String[]{"CHICAGO", "DALLAS", "HOUSTON", "AUSTIN"},
            "America/Denver", new String[]{"DENVER", "BOULDER", "SALT LAKE"},
            "America/Los_Angeles", new String[]{"LOS ANGELES", "SEATTLE", "SAN DIEGO", "PORTLAND"},
            "Asia/Kolkata", new String[]{"BENGALURU", "MUMBAI", "PUNE"},
            "Europe/London", new String[]{"LONDON", "MANCHESTER", "LEEDS"});
    private static final String[] FIRST = {"Aarav", "Maya", "Liam", "Sofia", "Noah", "Zara", "Ethan", "Priya", "Lucas",
            "Ava", "Omar", "Isla", "Mateo", "Chloe", "Ravi", "Emma", "Kenji", "Nora", "Diego", "Leah"};
    private static final String[] LAST = {"Rivera", "Patel", "Nguyen", "Smith", "Okafor", "Kim", "Garcia", "Muller",
            "Rossi", "Singh", "Brown", "Haddad", "Silva", "Novak", "Tanaka", "Lopez"};
    private static final String[] PEOPLE = {"J SMITH", "A PATEL", "M GARCIA", "R KIM", "S NOVAK", "T BROWN",
            "L SILVA", "K TANAKA", "D LOPEZ", "N SINGH"};
    private static final String[] WORDS = {"OAK", "STAR", "RIVER", "PINE", "LUCKY", "METRO", "CITY", "GOLD", "BAY",
            "NORTH", "ELM", "SUN"};

    /** Discretionary category: base weight, median amount (cents), lognormal sigma. */
    private record CatSpec(String code, double weight, long median, double sigma) {
    }

    private static final List<CatSpec> DISCRETIONARY = List.of(
            new CatSpec("GROC", 26, 6_500, 0.6), new CatSpec("DINE", 18, 2_800, 0.55),
            new CatSpec("SHOP", 13, 5_500, 0.85), new CatSpec("FUEL", 9, 4_500, 0.3),
            new CatSpec("TRNS", 7, 1_800, 0.6), new CatSpec("ENTR", 6, 3_500, 0.6),
            new CatSpec("HLTH", 4, 4_500, 0.8), new CatSpec("TRVL", 2.5, 32_000, 0.7),
            new CatSpec("GIFT", 2, 5_000, 0.7), new CatSpec("EDUC", 1.5, 9_000, 0.8),
            new CatSpec("PEER", 4, 4_000, 0.8), new CatSpec("CASH", 2.5, 10_000, 0.5),
            new CatSpec("FEES", 1, 1_200, 0.0));

    private static final double[] MONTH_VOLUME = {0.92, 0.88, 0.97, 0.98, 1.0, 1.02, 1.05, 1.04, 0.98, 1.0, 1.08, 1.25};

    private static double seasonal(String cat, int month) {
        return switch (cat) {
            case "SHOP" -> month == 11 ? 1.5 : month == 12 ? 1.9 : month == 1 ? 0.8 : 1.0;
            case "GIFT" -> month == 12 ? 3.5 : month == 2 ? 1.4 : month == 5 ? 1.3 : 1.0;
            case "TRVL" -> month == 6 ? 1.6 : month == 7 ? 1.9 : month == 8 ? 1.5 : month == 12 ? 1.6 : 1.0;
            case "DINE" -> month == 12 ? 1.25 : 1.0;
            case "ENTR" -> month == 7 || month == 12 ? 1.2 : 1.0;
            case "FUEL" -> month >= 6 && month <= 8 ? 1.15 : 1.0;
            case "EDUC" -> month == 8 ? 1.8 : month == 9 ? 1.6 : month == 1 ? 1.3 : 1.0;
            case "HLTH" -> month == 1 ? 1.5 : 1.0;
            default -> 1.0;
        };
    }

    private static final double[] ELECTRIC_FACTOR = {1.35, 1.25, 1.05, 0.9, 0.9, 1.1, 1.4, 1.35, 1.1, 0.9, 1.0, 1.2};

    /** Mutable working transaction; becomes a {@link Transaction} + {@link TransactionLabel} at the end. */
    static final class Draft {
        final long seq;
        final UserCtx user;
        final String accountId;
        String merchantId;
        LocalDateTime local;
        long amount;
        String descriptor;
        String txnType;
        String mcc;
        String trueCategory;
        boolean transfer;
        String transferGroup;
        boolean refund;
        Draft refundOf;
        String seriesId;
        final Set<String> issues = new TreeSet<>();
        String id;
        Instant utc;

        Draft(long seq, UserCtx user, String accountId) {
            this.seq = seq;
            this.user = user;
            this.accountId = accountId;
        }
    }

    static final class UserCtx {
        final int index;
        final UserProfile profile;
        final ZoneId zone;
        final String city;
        final String chk;
        final String sav;
        final String cc;
        final LocalDate activeFrom;
        final long monthlyNet;
        final boolean hasCar;
        final double budgetFactor;
        long checkingOpening;
        long savingsOpening;
        final List<Draft> drafts = new ArrayList<>();
        final Map<String, Long> recurringBudgetHints = new HashMap<>();

        UserCtx(int index, UserProfile profile, String city, String chk, String sav, String cc, LocalDate activeFrom,
                long monthlyNet, boolean hasCar, double budgetFactor) {
            this.index = index;
            this.profile = profile;
            this.zone = ZoneId.of(profile.timezone());
            this.city = city;
            this.chk = chk;
            this.sav = sav;
            this.cc = cc;
            this.activeFrom = activeFrom;
            this.monthlyNet = monthlyNet;
            this.hasCar = hasCar;
            this.budgetFactor = budgetFactor;
        }
    }

    public record Result(Dataset dataset, GroundTruth truth) {
    }

    private final GeneratorConfig cfg;
    private final Catalog catalog = new Catalog();
    private final LocalDate yearStart;
    private final LocalDate yearEnd;
    private long draftSeq = 0;
    private int seriesSeq = 0;
    private int transferSeq = 0;
    private final List<RecurringSeriesTruth> seriesTruth = new ArrayList<>();
    private final Map<String, SeriesBuilder> seriesBuilders = new LinkedHashMap<>();

    public SyntheticDataGenerator(GeneratorConfig cfg) {
        this.cfg = cfg;
        this.yearStart = LocalDate.of(cfg.year(), 1, 1);
        this.yearEnd = LocalDate.of(cfg.year(), 12, 31);
    }

    private Random rng(String stream, int index) {
        long h = cfg.seed() * 0x9E3779B97F4A7C15L + stream.hashCode() * 31L + index * 0x632BE59BD9B4E019L;
        return new Random(h);
    }

    public Result generate() {
        Dataset d = new Dataset();
        d.categories.addAll(catalog.categories);
        d.merchants.addAll(catalog.merchantRows());
        d.merchantCategories.addAll(catalog.merchantCategoryRows());
        d.categoryAliases.addAll(catalog.aliases);
        d.categoryRules.addAll(catalog.rules);

        List<UserCtx> users = new ArrayList<>();
        for (int i = 1; i <= cfg.users(); i++) {
            users.add(createUser(i, d));
        }
        // Structural (recurring) entries first so their count is known before discretionary allocation.
        long structural = 0;
        for (UserCtx u : users) {
            generateStructural(u);
            structural += u.drafts.size() + plannedDerivedCount(u);
        }
        long discretionary = cfg.transactions() - structural;
        if (discretionary < users.size() * 20L) {
            throw new IllegalStateException("Transaction target too small for " + users.size() + " users");
        }
        int[] allocation = allocateDiscretionary(users, discretionary);
        for (UserCtx u : users) {
            generateDiscretionary(u, allocation[u.index - 1]);
            injectTimezoneBoundaryCases(u);
            generateDerived(u);
        }

        List<Draft> all = new ArrayList<>();
        for (UserCtx u : users) {
            all.addAll(u.drafts);
        }
        for (Draft x : all) {
            x.utc = x.local.atZone(x.user.zone).toInstant();
        }
        all.sort(Comparator.comparing((Draft x) -> x.utc).thenComparing(x -> x.accountId).thenComparingLong(x -> x.seq));
        for (int i = 0; i < all.size(); i++) {
            all.get(i).id = String.format("TXN-%d-%08d", cfg.year(), i + 1);
        }

        GroundTruth g = new GroundTruth();
        Map<String, Long> balances = new HashMap<>();
        for (UserCtx u : users) {
            balances.put(u.chk, u.checkingOpening);
            if (u.sav != null) {
                balances.put(u.sav, u.savingsOpening);
            }
            if (u.cc != null) {
                balances.put(u.cc, 0L);
            }
        }
        for (Draft x : all) {
            long bal = balances.merge(x.accountId, x.amount, Long::sum);
            d.transactions.add(new Transaction(x.id, x.accountId, x.merchantId, x.utc, x.amount, "USD", x.descriptor,
                    x.txnType, x.mcc, bal));
            LocalDate localDate = x.local.toLocalDate();
            String budgetMonth = YearMonth.from(localDate).toString();
            if (!YearMonth.from(localDate).equals(YearMonth.from(x.utc.atZone(ZoneOffset.UTC)))) {
                x.issues.add(ISSUE_TIMEZONE);
            }
            g.transactionLabels.add(new TransactionLabel(x.id, x.trueCategory, x.transfer, x.transferGroup, x.refund,
                    x.refundOf == null ? null : x.refundOf.id, x.seriesId, localDate, budgetMonth,
                    x.issues.isEmpty() ? "NONE" : String.join("|", x.issues), expectedDecision(x)));
        }
        for (UserCtx u : users) {
            d.accounts.add(account(u, u.chk, "CHECKING", u.checkingOpening, balances.get(u.chk)));
            if (u.sav != null) {
                d.accounts.add(account(u, u.sav, "SAVINGS", u.savingsOpening, balances.get(u.sav)));
            }
            if (u.cc != null) {
                d.accounts.add(account(u, u.cc, "CREDIT_CARD", 0L, balances.get(u.cc)));
            }
        }
        for (SeriesBuilder sb : seriesBuilders.values()) {
            seriesTruth.add(sb.build(yearEnd));
        }
        g.recurringSeries.addAll(seriesTruth);

        Map<String, Category> categoryByCode = new HashMap<>();
        catalog.categories.forEach(c -> categoryByCode.put(c.categoryCode(), c));
        computeMonthlyActuals(all, categoryByCode, g);
        generateBudgets(users, d, g);
        computeExpectedInsights(d, g);
        return new Result(d, g);
    }

    // ------------------------------------------------------------------ master data
    private UserCtx createUser(int i, Dataset d) {
        Random r = rng("user", i);
        String userId = String.format("USR-%06d", i);
        String zone = ZONES[weighted(r, ZONE_WEIGHTS)];
        String[] cities = CITIES.get(zone);
        String city = cities[r.nextInt(cities.length)];
        int bandRoll = r.nextInt(100);
        String band = bandRoll < 30 ? "LOW" : bandRoll < 80 ? "MID" : "HIGH";
        long income = switch (band) {
            case "LOW" -> 3_000_000L + r.nextInt(2_500_000);
            case "MID" -> 5_500_000L + r.nextInt(6_500_000);
            default -> 12_000_000L + r.nextInt(18_000_000);
        };
        income = income / 100_000 * 100_000;
        int household = 1 + weighted(r, new int[]{30, 30, 20, 15, 5});
        LocalDate created;
        if (r.nextInt(100) < 8) {
            created = LocalDate.of(cfg.year(), 2 + r.nextInt(6), 1 + r.nextInt(28));
        } else {
            created = LocalDate.of(2018 + r.nextInt(8), 1 + r.nextInt(12), 1 + r.nextInt(28));
        }
        String name = FIRST[r.nextInt(FIRST.length)] + " " + LAST[r.nextInt(LAST.length)];
        UserProfile p = new UserProfile(userId, name, household, zone, band, income, city, created, "ACTIVE");
        d.users.add(p);

        int n = 1;
        String chk = String.format("ACC-%s-%02d", userId, n++);
        String sav = r.nextInt(100) < 70 ? String.format("ACC-%s-%02d", userId, n++) : null;
        String cc = r.nextInt(100) < 65 ? String.format("ACC-%s-%02d", userId, n) : null;
        LocalDate activeFrom = created.isAfter(yearStart) ? created : yearStart;
        long monthlyNet = Math.round(income * 0.75 / 12.0);
        UserCtx u = new UserCtx(i, p, city, chk, sav, cc, activeFrom, monthlyNet, r.nextInt(100) < 72,
                0.85 + r.nextDouble() * 0.35);
        u.checkingOpening = round100(monthlyNet * (0.6 + r.nextDouble()) + 50_000);
        u.savingsOpening = sav == null ? 0 : round100(monthlyNet * (1 + r.nextDouble() * 4));
        return u;
    }

    private Account account(UserCtx u, String id, String type, long opening, long closing) {
        LocalDate opened = u.profile.createdOn();
        return new Account(id, u.profile.userId(), type, opened, null, "OPEN", "USD", opening, closing);
    }

    // ------------------------------------------------------------------ structural (recurring) entries
    private void generateStructural(UserCtx u) {
        Random r = rng("struct", u.index);
        // Salary
        MerchantSpec employer = catalog.employers.get(r.nextInt(catalog.employers.size()));
        boolean biweekly = r.nextInt(100) < 60;
        long pay = biweekly ? round100(u.monthlyNet * 12 / 26.0) : round100(u.monthlyNet);
        SeriesBuilder salary = series(u, u.chk, employer.id(), "SALARY", biweekly ? "BIWEEKLY" : "MONTHLY", pay);
        String payDescriptor = employer.descriptors().get(r.nextInt(100) < 80 ? 0 : 1);
        if (biweekly) {
            LocalDate d = yearStart.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)).plusDays(r.nextBoolean() ? 0 : 7);
            for (; !d.isAfter(yearEnd); d = d.plusDays(14)) {
                if (!d.isBefore(u.activeFrom)) {
                    salaryDraft(u, employer, payDescriptor, d, pay, salary);
                }
            }
        } else {
            for (int m = 1; m <= 12; m++) {
                LocalDate d = previousBusinessDay(YearMonth.of(cfg.year(), m).atEndOfMonth());
                if (!d.isBefore(u.activeFrom)) {
                    salaryDraft(u, employer, payDescriptor, d, pay, salary);
                }
            }
        }

        // Housing: rent or mortgage
        boolean mortgage = !"LOW".equals(u.profile.incomeBand()) && r.nextInt(100) < 30;
        MerchantSpec payee = mortgage ? catalog.byName.get("Harbor Home Loans")
                : catalog.landlords.get(r.nextInt(catalog.landlords.size()));
        long housing = round100(u.monthlyNet * (0.25 + r.nextDouble() * 0.1));
        u.recurringBudgetHints.put(mortgage ? "MORT" : "RENT", housing);
        SeriesBuilder home = series(u, u.chk, payee.id(), mortgage ? "MORTGAGE" : "RENT", "MONTHLY", housing);
        int shiftMonth = !mortgage && r.nextInt(100) < 12 ? 2 + r.nextInt(11) : -1;
        for (int m = 1; m <= 12; m++) {
            LocalDate due = nextBusinessDay(LocalDate.of(cfg.year(), m, 1));
            if (due.isBefore(u.activeFrom)) {
                continue;
            }
            LocalDate posted = due;
            boolean shifted = m == shiftMonth && !previousBusinessDay(due.minusDays(1)).isBefore(u.activeFrom);
            if (shifted) {
                // Edge case: payment posts on the last business day of the previous month, so a naive
                // month-bucket detector sees two payments in month m-1 and a "skip" in month m.
                posted = previousBusinessDay(due.minusDays(1));
                home.shifted.add(due);
            }
            Draft x = draft(u, u.chk, payee, LocalDateTime.of(posted, LocalTime.of(7, 0)), -housing,
                    pick(r, payee.descriptors(), u), "ACH_DEBIT", payee.defaultCategory());
            x.seriesId = home.id;
            if (shifted) {
                x.issues.add(ISSUE_FALSE_SKIP);
            }
            home.hit(posted);
        }

        // Utilities
        utility(u, r, "Metro Power and Light", 6_000 + u.profile.householdSize() * 1_800L, true, u.chk);
        utility(u, r, "FiberLink Internet", 5_000 + r.nextInt(4_000), false, u.cc != null && r.nextBoolean() ? u.cc : u.chk);
        utility(u, r, "SignalWave Mobile", 3_500L * Math.min(u.profile.householdSize(), 4), false,
                u.cc != null && r.nextBoolean() ? u.cc : u.chk);
        if (r.nextInt(100) < 60) {
            utility(u, r, "AquaCity Water", 3_000 + u.profile.householdSize() * 1_200L, false, u.chk);
        }

        // Insurance
        if (u.hasCar && r.nextInt(100) < 70) {
            MerchantSpec ins = catalog.byName.get("Shieldline Auto Insurance");
            long premium = round100(9_000 + r.nextInt(13_000));
            monthlySeries(u, r, ins, u.chk, "INSURANCE", premium, 5 + r.nextInt(10), "ACH_DEBIT", "INSU", null);
        }

        // Subscriptions
        List<String[]> subs = List.of(new String[]{"StreamFlix", "1549"}, new String[]{"TuneWave Music", "1099"},
                new String[]{"CloudVault Storage", r.nextBoolean() ? "299" : "999"},
                new String[]{"DailyLedger News", "1200"}, new String[]{"FitPulse Gym", "3999"},
                new String[]{"ShopSphere Online", "1499"});
        int count = 1 + r.nextInt(5);
        List<Integer> order = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5));
        Collections.shuffle(order, r);
        for (int k = 0; k < count; k++) {
            String[] s = subs.get(order.get(k));
            MerchantSpec m = catalog.byName.get(s[0]);
            String acct = u.cc != null ? u.cc : u.chk;
            boolean prime = "ShopSphere Online".equals(s[0]);
            String category = prime ? "SUBS" : m.defaultCategory();
            String descriptor = prime ? "SHOPSPHERE PRIME MEMBERSHIP" : m.descriptors().get(0);
            subscription(u, r, m, acct, Long.parseLong(s[1]), category, descriptor);
        }
    }

    private void salaryDraft(UserCtx u, MerchantSpec employer, String descriptor, LocalDate d, long pay,
                             SeriesBuilder s) {
        Draft x = draft(u, u.chk, employer, LocalDateTime.of(d, LocalTime.of(6, 0)), pay, descriptor, "ACH_CREDIT",
                "SALR");
        x.seriesId = s.id;
        s.hit(d);
    }

    private void utility(UserCtx u, Random r, String name, long base, boolean seasonal, String account) {
        MerchantSpec m = catalog.byName.get(name);
        int day = 8 + r.nextInt(15);
        long amount = round100(base);
        u.recurringBudgetHints.merge("UTIL", amount, Long::sum);
        monthlySeries(u, r, m, account, "UTILITY", amount, day, account.endsWith("01") ? "ACH_DEBIT" : "CARD_PURCHASE",
                "UTIL", seasonal ? ELECTRIC_FACTOR : null);
    }

    private void monthlySeries(UserCtx u, Random r, MerchantSpec m, String account, String kind, long amount, int day,
                               String txnType, String category, double[] monthFactor) {
        SeriesBuilder s = series(u, account, m.id(), kind, "MONTHLY", amount);
        for (int mo = 1; mo <= 12; mo++) {
            LocalDate d = LocalDate.of(cfg.year(), mo, Math.min(day, 28));
            if (d.isBefore(u.activeFrom)) {
                continue;
            }
            long amt = amount;
            if (monthFactor != null) {
                amt = Math.round(amount * monthFactor[mo - 1] * (0.92 + r.nextDouble() * 0.16));
            }
            Draft x = draft(u, account, m, LocalDateTime.of(d, LocalTime.of(7, 30)), -amt, m.descriptors().get(0),
                    txnType, category);
            x.seriesId = s.id;
            s.hit(d);
        }
    }

    private void subscription(UserCtx u, Random r, MerchantSpec m, String account, long price, String category,
                              String descriptor) {
        int day = 1 + r.nextInt(28);
        int startMonth = r.nextInt(100) < 20 ? 2 + r.nextInt(6) : 1;
        int endMonth = r.nextInt(100) < 15 ? 6 + r.nextInt(6) : 12;
        int skipMonth = r.nextInt(100) < 10 && endMonth - startMonth >= 3 ? startMonth + 1 + r.nextInt(endMonth - startMonth - 1) : -1;
        int priceMonth = (m.name().equals("StreamFlix") || m.name().equals("TuneWave Music")) && r.nextInt(100) < 25
                ? 4 + r.nextInt(6) : -1;
        long newPrice = price + 100 + r.nextInt(2) * 100;
        if (LocalDate.of(cfg.year(), endMonth, 28).isBefore(u.activeFrom)) {
            return;
        }
        SeriesBuilder s = series(u, account, m.id(), "SUBSCRIPTION", "MONTHLY", price);
        u.recurringBudgetHints.merge(category, price, Long::sum);
        for (int mo = startMonth; mo <= endMonth; mo++) {
            LocalDate d = LocalDate.of(cfg.year(), mo, Math.min(day, YearMonth.of(cfg.year(), mo).lengthOfMonth()));
            if (d.isBefore(u.activeFrom)) {
                continue;
            }
            if (mo == skipMonth) {
                // Edge case: genuinely missed charge (failed card / paused) - should be flagged as missed.
                s.missed.add(d);
                continue;
            }
            long amt = priceMonth > 0 && mo >= priceMonth ? newPrice : price;
            if (priceMonth > 0 && mo == priceMonth) {
                s.priceChange = d;
            }
            // Billing systems charge overnight local time; this naturally creates timezone-boundary rows.
            Draft x = draft(u, account, m, LocalDateTime.of(d, LocalTime.of(2 + r.nextInt(4), r.nextInt(60))), -amt,
                    descriptor, "CARD_PURCHASE", category);
            x.seriesId = s.id;
            s.hit(d);
        }
        if (endMonth < 12) {
            s.status = "ENDED";
        }
    }

    /** Number of entries {@link #generateDerived} will add: savings transfers, interest, card payments. */
    private int plannedDerivedCount(UserCtx u) {
        int months = 12 - u.activeFrom.getMonthValue() + 1;
        int count = 0;
        if (u.sav != null) {
            count += countTransferDates(u) * 2 + months;
        }
        if (u.cc != null) {
            count += countCardPaymentDates(u) * 2;
        }
        return count;
    }

    private int countTransferDates(UserCtx u) {
        int c = 0;
        for (int m = 1; m <= 12; m++) {
            if (!savingsTransferDate(m).isBefore(u.activeFrom)) {
                c++;
            }
        }
        return c;
    }

    private int countCardPaymentDates(UserCtx u) {
        int c = 0;
        for (int m = 2; m <= 12; m++) {
            if (!YearMonth.of(cfg.year(), m - 1).atDay(1).isBefore(u.activeFrom.withDayOfMonth(1))) {
                c++;
            }
        }
        return c;
    }

    private LocalDate savingsTransferDate(int month) {
        return nextBusinessDay(LocalDate.of(cfg.year(), month, 3));
    }

    // ------------------------------------------------------------------ discretionary entries
    private int[] allocateDiscretionary(List<UserCtx> users, long total) {
        double[] w = new double[users.size()];
        double sum = 0;
        for (UserCtx u : users) {
            Random r = rng("weight", u.index);
            double band = switch (u.profile.incomeBand()) {
                case "LOW" -> 0.85;
                case "HIGH" -> 1.25;
                default -> 1.0;
            };
            double months = 12 - u.activeFrom.getMonthValue() + 1;
            w[u.index - 1] = Math.exp(r.nextGaussian() * 0.35) * band * (1 + 0.1 * (u.profile.householdSize() - 1))
                    * months / 12.0;
            sum += w[u.index - 1];
        }
        int[] alloc = new int[users.size()];
        double[] rem = new double[users.size()];
        long assigned = 0;
        for (int i = 0; i < w.length; i++) {
            double exact = total * w[i] / sum;
            alloc[i] = (int) Math.floor(exact);
            rem[i] = exact - alloc[i];
            assigned += alloc[i];
        }
        Integer[] idx = new Integer[w.length];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> rem[b] != rem[a] ? Double.compare(rem[b], rem[a]) : Integer.compare(a, b));
        for (int k = 0; assigned < total; k++, assigned++) {
            alloc[idx[k % idx.length]]++;
        }
        return alloc;
    }

    private void generateDiscretionary(UserCtx u, int slots) {
        Random r = rng("disc", u.index);
        double[] catWeights = new double[DISCRETIONARY.size()];
        for (int i = 0; i < catWeights.length; i++) {
            CatSpec c = DISCRETIONARY.get(i);
            double w = c.weight();
            if (c.code().equals("FUEL") && !u.hasCar) {
                w = 0;
            }
            if (c.code().equals("TRNS") && !u.hasCar) {
                w *= 2;
            }
            switch (u.profile.incomeBand()) {
                case "HIGH" -> {
                    if (Set.of("DINE", "SHOP").contains(c.code())) w *= 1.3;
                    if (c.code().equals("TRVL")) w *= 2;
                }
                case "LOW" -> {
                    if (c.code().equals("GROC")) w *= 1.1;
                    if (c.code().equals("CASH")) w *= 1.5;
                    if (c.code().equals("FEES")) w *= 2;
                }
                default -> {
                }
            }
            catWeights[i] = w;
        }
        int firstMonth = u.activeFrom.getMonthValue();
        double incomeMult = switch (u.profile.incomeBand()) {
            case "LOW" -> 0.8;
            case "HIGH" -> 1.5;
            default -> 1.0;
        };
        int produced = 0;
        while (produced < slots) {
            int month = pickMonth(r, firstMonth);
            double[] w = new double[catWeights.length];
            for (int i = 0; i < w.length; i++) {
                w[i] = catWeights[i] * seasonal(DISCRETIONARY.get(i).code(), month);
            }
            CatSpec cat = DISCRETIONARY.get(weighted(r, w));
            YearMonth ym = YearMonth.of(cfg.year(), month);
            int minDay = ym.equals(YearMonth.from(u.activeFrom)) ? u.activeFrom.getDayOfMonth() : 1;
            LocalDate date = ym.atDay(minDay + r.nextInt(ym.lengthOfMonth() - minDay + 1));
            Draft purchase = purchase(u, r, cat, date, incomeMult);
            produced++;
            double refundRate = switch (cat.code()) {
                case "SHOP" -> 0.06;
                case "TRVL" -> 0.08;
                case "EDUC" -> 0.04;
                case "ENTR" -> 0.03;
                case "HLTH" -> 0.02;
                case "GROC" -> 0.015;
                case "DINE" -> 0.01;
                default -> 0.0;
            };
            if (produced < slots && purchase.merchantId != null && r.nextDouble() < refundRate) {
                LocalDateTime when = purchase.local.plusDays(3 + r.nextInt(33)).withHour(9 + r.nextInt(9));
                if (!when.toLocalDate().isAfter(yearEnd)) {
                    refund(u, r, purchase, when);
                    produced++;
                }
            }
        }
    }

    private int pickMonth(Random r, int firstMonth) {
        double[] w = new double[12];
        for (int m = firstMonth; m <= 12; m++) {
            w[m - 1] = MONTH_VOLUME[m - 1];
        }
        return weighted(r, w) + 1;
    }

    private Draft purchase(UserCtx u, Random r, CatSpec cat, LocalDate date, double incomeMult) {
        String code = cat.code();
        LocalTime time = switch (code) {
            case "DINE" -> LocalTime.of(11 + r.nextInt(12), r.nextInt(60));
            case "GROC", "SHOP" -> LocalTime.of(8 + r.nextInt(14), r.nextInt(60));
            case "FEES" -> LocalTime.of(4, 0);
            default -> LocalTime.of(7 + r.nextInt(15), r.nextInt(60));
        };
        LocalDateTime when = LocalDateTime.of(date, time);
        long amount;
        if (code.equals("FEES")) {
            amount = new long[]{1_200, 3_500, 300, 1_500}[r.nextInt(4)];
        } else if (code.equals("CASH")) {
            amount = (2 + r.nextInt(14)) * 2_000L;
        } else {
            double mult = code.equals("FUEL") ? 1.0 : incomeMult;
            amount = Math.max(150, Math.round(cat.median() * mult * Math.exp(r.nextGaussian() * cat.sigma())));
            if (code.equals("PEER")) {
                amount = Math.max(500, amount / 500 * 500);
            }
        }
        String account = u.chk;
        if (u.cc != null && !Set.of("CASH", "PEER", "FEES").contains(code) && r.nextInt(100) < 60) {
            account = u.cc;
        }
        switch (code) {
            case "FEES" -> {
                MerchantSpec bank = catalog.byName.get("First Synthetic Bank");
                String desc = new String[]{"MONTHLY SERVICE FEE", "OVERDRAFT FEE", "FOREIGN TXN FEE", "WIRE FEE"}[r.nextInt(4)];
                return draft(u, u.chk, bank, when, -amount, desc, "FEE", "FEES");
            }
            case "CASH" -> {
                MerchantSpec atm = catalog.byName.get("ATM Network");
                return draft(u, u.chk, atm, when, -amount, pick(r, atm.descriptors(), u), "ATM", "CASH");
            }
            case "PEER" -> {
                MerchantSpec p2p = catalog.byName.get("QuickPay P2P");
                return draft(u, u.chk, p2p, when, -amount, "QUICKPAY TO " + PEOPLE[r.nextInt(PEOPLE.length)],
                        "P2P", "PEER");
            }
            default -> {
            }
        }
        if (r.nextInt(1000) < 20) {
            // Merchant not in master data: only descriptor and (sometimes) MCC are available.
            String mcc = r.nextInt(100) < 60 ? typicalMcc(code) : null;
            String desc = "POS " + WORDS[r.nextInt(WORDS.length)] + " " + WORDS[r.nextInt(WORDS.length)] + " "
                    + (100 + r.nextInt(900));
            Draft x = new Draft(draftSeq++, u, account);
            x.local = when;
            x.amount = -amount;
            x.descriptor = desc;
            x.txnType = "CARD_PURCHASE";
            x.mcc = mcc;
            x.trueCategory = code;
            u.drafts.add(x);
            return x;
        }
        List<MerchantSpec> candidates = catalog.merchantsByCategory.get(code);
        MerchantSpec m = candidates.get(r.nextInt(candidates.size()));
        return draft(u, account, m, when, -amount, pick(r, m.descriptors(), u), "CARD_PURCHASE", code);
    }

    private String typicalMcc(String code) {
        for (MerchantSpec m : catalog.merchants) {
            if (code.equals(m.defaultCategory()) && m.mcc() != null) {
                return m.mcc();
            }
        }
        return null;
    }

    private void refund(UserCtx u, Random r, Draft original, LocalDateTime when) {
        long amount = r.nextInt(100) < 70 ? -original.amount
                : Math.max(100, Math.round(-original.amount * (0.2 + r.nextDouble() * 0.6)));
        boolean mislabelled = r.nextInt(100) < 25;
        String desc = mislabelled ? original.descriptor
                : (r.nextBoolean() ? "REFUND " : "RETURN CREDIT ") + original.descriptor;
        Draft x = new Draft(draftSeq++, u, original.accountId);
        x.merchantId = original.merchantId;
        x.local = when;
        x.amount = amount;
        x.descriptor = desc;
        // Edge case: the bank posts some refunds with the purchase transaction type, so a naive
        // type-based classifier books them as a new expense.
        x.txnType = mislabelled ? "CARD_PURCHASE" : "CARD_REFUND";
        x.mcc = original.mcc;
        x.trueCategory = original.trueCategory;
        x.refund = true;
        x.refundOf = original;
        if (mislabelled) {
            x.issues.add(ISSUE_REFUND);
        }
        u.drafts.add(x);
    }

    // ------------------------------------------------------------------ derived entries
    private void generateDerived(UserCtx u) {
        Random r = rng("derived", u.index);
        MerchantSpec bank = catalog.byName.get("First Synthetic Bank");
        if (u.sav != null) {
            long amount = round100(u.monthlyNet * (0.05 + r.nextDouble() * 0.1));
            String last4 = String.valueOf(1000 + r.nextInt(9000));
            for (int m = 1; m <= 12; m++) {
                LocalDate d = savingsTransferDate(m);
                if (d.isBefore(u.activeFrom)) {
                    continue;
                }
                boolean delayed = r.nextInt(100) < 15;
                String group = String.format("TRG-%07d", ++transferSeq);
                Draft out = draft(u, u.chk, bank, LocalDateTime.of(d, LocalTime.of(9, 15)), -amount,
                        delayed ? "WEB PMT " + (100000 + r.nextInt(900000)) : "ONLINE TRANSFER TO SAV ..." + last4,
                        delayed ? "ACH_DEBIT" : "TRANSFER", "XFER");
                LocalDate inDate = delayed ? nextBusinessDay(d.plusDays(1 + r.nextInt(2))) : d;
                Draft in = draft(u, u.sav, bank, LocalDateTime.of(inDate, LocalTime.of(delayed ? 10 : 9, 16)), amount,
                        delayed ? "ACH CREDIT REF " + (100000 + r.nextInt(900000)) : "ONLINE TRANSFER FROM CHK ..." + last4,
                        delayed ? "ACH_CREDIT" : "TRANSFER", "XFER");
                markTransfer(out, in, group, delayed);
            }
            // Monthly interest derived from the running savings balance (4.00% APY simple monthly).
            long balance = u.savingsOpening;
            List<Draft> savDrafts = u.drafts.stream().filter(x -> x.accountId.equals(u.sav)).toList();
            for (int m = u.activeFrom.getMonthValue(); m <= 12; m++) {
                YearMonth ym = YearMonth.of(cfg.year(), m);
                for (Draft x : savDrafts) {
                    if (YearMonth.from(x.local).equals(ym)) {
                        balance += x.amount;
                    }
                }
                long interest = Math.max(1, Math.round(balance * 0.04 / 12.0));
                balance += interest;
                draft(u, u.sav, bank, LocalDateTime.of(ym.atEndOfMonth(), LocalTime.of(12, 0)), interest,
                        "INTEREST PAYMENT", "INTEREST", "INTR");
            }
        }
        if (u.cc != null) {
            // Card payment on the 25th pays the previous month's net card activity (derived, not random).
            Map<YearMonth, Long> monthly = new HashMap<>();
            for (Draft x : u.drafts) {
                if (x.accountId.equals(u.cc)) {
                    monthly.merge(YearMonth.from(x.local), x.amount, Long::sum);
                }
            }
            String last4 = String.valueOf(1000 + r.nextInt(9000));
            for (int m = 2; m <= 12; m++) {
                YearMonth prev = YearMonth.of(cfg.year(), m - 1);
                if (prev.atDay(1).isBefore(u.activeFrom.withDayOfMonth(1))) {
                    continue;
                }
                long due = -monthly.getOrDefault(prev, 0L);
                if (due <= 0) {
                    due = 100;
                }
                LocalDate d = nextBusinessDay(LocalDate.of(cfg.year(), m, 25));
                boolean opaque = r.nextInt(100) < 30;
                String group = String.format("TRG-%07d", ++transferSeq);
                Draft out = draft(u, u.chk, bank, LocalDateTime.of(d, LocalTime.of(8, 0)), -due,
                        opaque ? "WEB PMT FSB CARD " + (100000 + r.nextInt(900000)) : "CARD SVCS ONLINE PMT ..." + last4,
                        "BILL_PAY", "CCPY");
                Draft in = draft(u, u.cc, bank, LocalDateTime.of(d.plusDays(opaque ? 1 : 0), LocalTime.of(8, 5)), due,
                        "PAYMENT RECEIVED THANK YOU", "PAYMENT", "CCPY");
                markTransfer(out, in, group, true);
            }
        }
    }

    private void markTransfer(Draft out, Draft in, String group, boolean risky) {
        for (Draft x : List.of(out, in)) {
            x.transfer = true;
            x.transferGroup = group;
            if (risky) {
                x.issues.add(ISSUE_TRANSFER);
            }
        }
    }

    /** Move a few purchases to late evening on month-end (or early morning on the 1st for UTC+ zones). */
    private void injectTimezoneBoundaryCases(UserCtx u) {
        Random r = rng("tz", u.index);
        Set<Draft> refunded = new HashSet<>();
        for (Draft x : u.drafts) {
            if (x.refundOf != null) {
                refunded.add(x.refundOf);
            }
        }
        List<Draft> candidates = u.drafts.stream()
                .filter(x -> "CARD_PURCHASE".equals(x.txnType) && x.seriesId == null && !x.refund && !refunded.contains(x))
                .toList();
        for (int k = 0; k < 3 && !candidates.isEmpty(); k++) {
            Draft x = candidates.get(r.nextInt(candidates.size()));
            YearMonth ym = YearMonth.from(x.local);
            int offset = u.zone.getRules().getOffset(x.local.atZone(u.zone).toInstant()).getTotalSeconds();
            LocalDateTime moved;
            if (offset < 0) {
                moved = LocalDateTime.of(ym.atEndOfMonth(), LocalTime.of(21 + r.nextInt(3), r.nextInt(60)));
            } else if (offset > 0) {
                moved = LocalDateTime.of(ym.atDay(1), LocalTime.of(r.nextInt(Math.min(5, offset / 3600)), r.nextInt(60)));
            } else {
                continue;
            }
            if (!moved.toLocalDate().isBefore(u.activeFrom)) {
                x.local = moved;
            }
        }
    }

    // ------------------------------------------------------------------ truth aggregates, budgets, insights
    private void computeMonthlyActuals(List<Draft> all, Map<String, Category> cats, GroundTruth g) {
        Map<String, long[]> agg = new TreeMap<>();
        for (Draft x : all) {
            Category c = cats.get(x.trueCategory);
            if (x.transfer || c == null || !c.isExpense()) {
                continue;
            }
            String key = x.user.profile.userId() + "|" + YearMonth.from(x.local) + "|" + x.trueCategory;
            long[] v = agg.computeIfAbsent(key, k -> new long[2]);
            v[0] += -x.amount;
            v[1]++;
        }
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            String[] k = e.getKey().split("\\|");
            g.monthlyActuals.add(new MonthlyActual(k[0], k[1], k[2], e.getValue()[0], (int) e.getValue()[1]));
        }
    }

    private static final Map<String, List<String>> MISMATCH_VARIANTS = Map.of(
            "GROC", List.of("GROCERY", "groceries ", "Groc"),
            "DINE", List.of("DINING", "RESTAURANTS"),
            "SUBS", List.of("SUBSCRIPTIONS"),
            "ENTR", List.of("ENTERTAINMENT"),
            "FUEL", List.of("GAS"));
    private static final List<String> UNMAPPABLE = List.of("PETS", "HOBBY", "CHILDCARE");

    private void generateBudgets(List<UserCtx> users, Dataset d, GroundTruth g) {
        Map<String, Long> annual = new HashMap<>();
        for (MonthlyActual a : g.monthlyActuals) {
            annual.merge(a.userId() + "|" + a.categoryCode(), a.spendCents(), Long::sum);
        }
        Set<String> aliasCodes = new HashSet<>();
        catalog.aliases.forEach(a -> aliasCodes.add(a.aliasCode()));
        for (UserCtx u : users) {
            Random r = rng("budget", u.index);
            String userId = u.profile.userId();
            int months = 12 - u.activeFrom.getMonthValue() + 1;
            List<String> cats = new ArrayList<>(List.of("GROC", "DINE", "SHOP", "ENTR", "UTIL", "SUBS", "TRNS"));
            cats.add(u.recurringBudgetHints.containsKey("MORT") ? "MORT" : "RENT");
            if (u.hasCar) cats.add("FUEL");
            if (r.nextInt(100) < 50) cats.add("HLTH");
            if (r.nextInt(100) < 30) cats.add("TRVL");
            for (String cat : cats) {
                long avg = annual.getOrDefault(userId + "|" + cat, 0L) / months;
                if (avg <= 0) {
                    continue;
                }
                // Budgets are derived from the household's own average spend scaled by a per-user
                // discipline factor, so over/under-budget outcomes follow seasonality, not random labels.
                boolean fixed = Set.of("RENT", "MORT", "UTIL", "SUBS").contains(cat);
                long amount = round1000(avg * (fixed ? 1.05 : u.budgetFactor));
                String supplied = cat;
                String issue = "NONE";
                String decision = "USE_AS_IS";
                List<String> variants = MISMATCH_VARIANTS.get(cat);
                if (variants != null && r.nextInt(100) < 4) {
                    supplied = variants.get(r.nextInt(variants.size()));
                    issue = ISSUE_BUDGET;
                    decision = "MAP_TO_" + cat;
                }
                addBudgetYear(u, d, g, supplied, cat, amount, issue, decision, aliasCodes);
            }
            if (r.nextInt(100) < 3) {
                String bogus = UNMAPPABLE.get(r.nextInt(UNMAPPABLE.size()));
                addBudgetYear(u, d, g, bogus, null, round1000(5_000 + r.nextInt(20_000)), ISSUE_BUDGET,
                        "FLAG_UNMAPPED", aliasCodes);
            }
        }
    }

    private void addBudgetYear(UserCtx u, Dataset d, GroundTruth g, String supplied, String expected, long amount,
                               String issue, String decision, Set<String> aliasCodes) {
        String idCode = supplied.trim().toUpperCase().replace(' ', '_');
        for (int m = u.activeFrom.getMonthValue(); m <= 12; m++) {
            YearMonth ym = YearMonth.of(cfg.year(), m);
            String id = String.format("BUD-%s-%s-%s", u.profile.userId(), ym, idCode);
            d.budgets.add(new Budget(id, u.profile.userId(), ym.toString(), supplied, amount, "USD", ym.atDay(1),
                    "ACTIVE"));
            g.budgetLabels.add(new BudgetLabel(id, supplied, expected == null ? "UNMAPPED" : expected, issue, decision));
        }
    }

    private void computeExpectedInsights(Dataset d, GroundTruth g) {
        Map<String, MonthlyActual> actuals = new HashMap<>();
        for (MonthlyActual a : g.monthlyActuals) {
            actuals.put(a.userId() + "|" + a.month() + "|" + a.categoryCode(), a);
        }
        Map<String, String> expectedCode = new HashMap<>();
        g.budgetLabels.forEach(b -> expectedCode.put(b.budgetId(), b.expectedCategoryCode()));
        int seq = 0;
        for (Budget b : d.budgets) {
            String code = expectedCode.get(b.budgetId());
            if ("UNMAPPED".equals(code)) {
                continue;
            }
            MonthlyActual a = actuals.get(b.userId() + "|" + b.budgetMonth() + "|" + code);
            long actual = a == null ? 0 : a.spendCents();
            String type = InsightRules.budgetInsightType(actual, b.amountCents());
            if (type != null) {
                g.expectedInsights.add(new Insight(String.format("INS-%07d", ++seq), b.userId(), b.budgetMonth(), type,
                        code, actual, b.amountCents(), InsightRules.severity(type, actual, b.amountCents()),
                        "budget_id=" + b.budgetId()));
            }
        }
        for (RecurringSeriesTruth s : g.recurringSeries) {
            if (s.priceChangeDate() != null) {
                g.expectedInsights.add(new Insight(String.format("INS-%07d", ++seq), s.userId(),
                        YearMonth.from(LocalDate.parse(s.priceChangeDate())).toString(), "RECURRING_PRICE_CHANGE",
                        null, 0, s.expectedAmountCents(), "LOW", "series_id=" + s.seriesId()));
            }
            if (s.missedDates() != null) {
                for (String md : s.missedDates().split(";")) {
                    g.expectedInsights.add(new Insight(String.format("INS-%07d", ++seq), s.userId(),
                            YearMonth.from(LocalDate.parse(md)).toString(), "MISSED_RECURRING", null, 0,
                            s.expectedAmountCents(), "MEDIUM", "series_id=" + s.seriesId()));
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers
    private String expectedDecision(Draft x) {
        if (x.transfer) {
            return "EXCLUDE_FROM_SPEND";
        }
        if (x.refund) {
            return "OFFSET_ORIGINAL_CATEGORY";
        }
        if (x.issues.contains(ISSUE_TIMEZONE)) {
            return "ASSIGN_LOCAL_MONTH";
        }
        if (x.issues.contains(ISSUE_FALSE_SKIP)) {
            return "MATCH_TO_NEXT_DUE_DATE";
        }
        return "CATEGORIZE";
    }

    private Draft draft(UserCtx u, String account, MerchantSpec m, LocalDateTime when, long amount, String descriptor,
                        String txnType, String trueCategory) {
        Draft x = new Draft(draftSeq++, u, account);
        x.merchantId = m.id();
        x.local = when;
        x.amount = amount;
        x.descriptor = descriptor;
        x.txnType = txnType;
        x.mcc = m.mcc();
        x.trueCategory = trueCategory;
        u.drafts.add(x);
        return x;
    }

    private String pick(Random r, List<String> templates, UserCtx u) {
        String t = templates.get(r.nextInt(templates.size()));
        return t.replace("{n}", String.valueOf(100 + r.nextInt(900)))
                .replace("{city}", u.city)
                .replace("{code}", Long.toString(Math.abs(r.nextLong()) % 1_679_616L, 36).toUpperCase())
                .replace("{person}", PEOPLE[r.nextInt(PEOPLE.length)]);
    }

    private SeriesBuilder series(UserCtx u, String account, String merchantId, String kind, String cadence, long amount) {
        String id = String.format("REC-%06d", ++seriesSeq);
        SeriesBuilder s = new SeriesBuilder(id, u.profile.userId(), account, merchantId, kind, cadence, amount);
        seriesBuilders.put(id, s);
        return s;
    }

    private static final class SeriesBuilder {
        final String id, userId, accountId, merchantId, kind, cadence;
        final long amount;
        LocalDate start, end, priceChange;
        int occurrences;
        String status = "ACTIVE";
        final List<LocalDate> missed = new ArrayList<>();
        final List<LocalDate> shifted = new ArrayList<>();

        void hit(LocalDate d) {
            if (start == null || d.isBefore(start)) {
                start = d;
            }
            if (end == null || d.isAfter(end)) {
                end = d;
            }
            occurrences++;
        }

        SeriesBuilder(String id, String userId, String accountId, String merchantId, String kind, String cadence,
                      long amount) {
            this.id = id;
            this.userId = userId;
            this.accountId = accountId;
            this.merchantId = merchantId;
            this.kind = kind;
            this.cadence = cadence;
            this.amount = amount;
        }

        RecurringSeriesTruth build(LocalDate yearEnd) {
            String issue = !missed.isEmpty() ? ISSUE_TRUE_SKIP : !shifted.isEmpty() ? ISSUE_FALSE_SKIP : "NONE";
            return new RecurringSeriesTruth(id, userId, accountId, merchantId, kind, cadence, amount, start, end,
                    status, occurrences, join(missed), join(shifted), priceChange == null ? null : priceChange.toString(),
                    issue);
        }

        private static String join(List<LocalDate> dates) {
            if (dates.isEmpty()) {
                return null;
            }
            StringJoiner j = new StringJoiner(";");
            dates.forEach(d -> j.add(d.toString()));
            return j.toString();
        }
    }

    private static int weighted(Random r, int[] weights) {
        double[] w = new double[weights.length];
        for (int i = 0; i < w.length; i++) {
            w[i] = weights[i];
        }
        return weighted(r, w);
    }

    private static int weighted(Random r, double[] weights) {
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        double x = r.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            x -= weights[i];
            if (x < 0) {
                return i;
            }
        }
        return weights.length - 1;
    }

    private static LocalDate nextBusinessDay(LocalDate d) {
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    private static LocalDate previousBusinessDay(LocalDate d) {
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    private static long round100(double cents) {
        return Math.round(cents / 100.0) * 100;
    }

    private static long round1000(double cents) {
        return Math.max(1_000, Math.round(cents / 1_000.0) * 1_000);
    }
}
