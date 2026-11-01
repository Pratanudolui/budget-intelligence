package com.yottaflex.budget.generator;

import com.yottaflex.budget.model.Category;
import com.yottaflex.budget.model.CategoryAlias;
import com.yottaflex.budget.model.CategoryRule;
import com.yottaflex.budget.model.Merchant;
import com.yottaflex.budget.model.MerchantCategory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static reference data: category taxonomy, fictional merchant catalog (with descriptor aliases),
 * legacy category aliases and the categorization rule set. All merchant names are invented.
 */
public final class Catalog {

    public static final LocalDate TAXONOMY_EFFECTIVE = LocalDate.of(2025, 1, 1);

    /** Merchant definition used by the generator. truthMix gives the ground-truth category split for purchases. */
    public record MerchantSpec(String id, String name, String type, String defaultCategory, String mcc,
                               List<String> descriptors, Map<String, Integer> truthMix) {
    }

    public final List<Category> categories = new ArrayList<>();
    public final List<MerchantSpec> merchants = new ArrayList<>();
    public final List<CategoryAlias> aliases = new ArrayList<>();
    public final List<CategoryRule> rules = new ArrayList<>();
    public final Map<String, List<MerchantSpec>> merchantsByCategory = new LinkedHashMap<>();
    public final List<MerchantSpec> employers = new ArrayList<>();
    public final List<MerchantSpec> landlords = new ArrayList<>();
    public final Map<String, MerchantSpec> byName = new LinkedHashMap<>();

    private int merchantSeq = 0;
    private int ruleSeq = 0;

    public Catalog() {
        buildTaxonomy();
        buildMerchants();
        buildAliases();
        buildRules();
    }

    // ------------------------------------------------------------------ taxonomy
    private void buildTaxonomy() {
        cat("INCM", "Income", null, "GROUP", false);
        cat("HOME", "Home", null, "GROUP", true);
        cat("FOOD", "Food", null, "GROUP", true);
        cat("MOVE", "Transport", null, "GROUP", true);
        cat("LIFE", "Lifestyle", null, "GROUP", false);
        cat("FINC", "Financial", null, "GROUP", true);
        cat("XFRS", "Transfers", null, "GROUP", false);
        cat("MISC", "Miscellaneous", null, "GROUP", false);

        cat("SALR", "Salary", "INCM", "INCOME", false);
        cat("INTR", "Interest", "INCM", "INCOME", false);
        cat("RENT", "Rent", "HOME", "EXPENSE", true);
        cat("MORT", "Mortgage", "HOME", "EXPENSE", true);
        cat("UTIL", "Utilities", "HOME", "EXPENSE", true);
        cat("GROC", "Groceries", "FOOD", "EXPENSE", true);
        cat("DINE", "Dining", "FOOD", "EXPENSE", false);
        cat("FUEL", "Fuel", "MOVE", "EXPENSE", true);
        cat("TRNS", "Transit and Rideshare", "MOVE", "EXPENSE", false);
        cat("SHOP", "Shopping", "LIFE", "EXPENSE", false);
        cat("ENTR", "Entertainment", "LIFE", "EXPENSE", false);
        cat("SUBS", "Subscriptions", "LIFE", "EXPENSE", false);
        cat("HLTH", "Health and Fitness", "LIFE", "EXPENSE", true);
        cat("TRVL", "Travel", "LIFE", "EXPENSE", false);
        cat("GIFT", "Gifts and Donations", "LIFE", "EXPENSE", false);
        cat("EDUC", "Education", "LIFE", "EXPENSE", false);
        cat("INSU", "Insurance", "FINC", "EXPENSE", true);
        cat("FEES", "Bank Fees", "FINC", "EXPENSE", false);
        cat("PEER", "Peer Payments", "MISC", "EXPENSE", false);
        cat("CASH", "Cash Withdrawal", "MISC", "EXPENSE", false);
        cat("XFER", "Internal Transfer", "XFRS", "TRANSFER", false);
        cat("CCPY", "Credit Card Payment", "XFRS", "TRANSFER", false);
        cat("UNCT", "Uncategorized", "MISC", "UNKNOWN", false);
    }

    private void cat(String code, String name, String parent, String kind, boolean essential) {
        categories.add(new Category(code, name, parent, kind, essential, TAXONOMY_EFFECTIVE, "ACTIVE"));
    }

    // ------------------------------------------------------------------ merchants
    private void buildMerchants() {
        m("Northwind Market", "RETAIL", "GROC", "5411", List.of("NORTHWIND MKT #{n}", "NWM*NORTHWIND {n}", "NORTHWIND MARKET {city}"));
        m("FreshField Grocers", "RETAIL", "GROC", "5411", List.of("FRESHFIELD GROCERS {n}", "FFG*FRESHFIELD #{n}"));
        m("GreenBasket Foods", "RETAIL", "GROC", "5411", List.of("GREENBASKET FOODS", "GRNBSKT {city}"));
        m("Corner Pantry", "RETAIL", "GROC", "5499", List.of("CORNER PANTRY {n}", "SQ *CORNER PANTRY"));

        m("Bluebird Cafe", "RETAIL", "DINE", "5814", List.of("BLUEBIRD CAFE {city}", "SQ *BLUEBIRD CAFE"));
        m("Tandoor House", "RETAIL", "DINE", "5812", List.of("TANDOOR HOUSE", "TST* TANDOOR HOUSE {n}"));
        m("Slice Republic Pizza", "RETAIL", "DINE", "5812", List.of("SLICE REPUBLIC #{n}", "SLICEREP ONLINE ORDER"));
        m("Sunrise Diner", "RETAIL", "DINE", "5812", List.of("SUNRISE DINER {city}"));
        m("Noodle Lab", "RETAIL", "DINE", "5812", List.of("NOODLE LAB {n}", "TST* NOODLELAB"));
        m("BurgerBarn", "RETAIL", "DINE", "5814", List.of("BURGERBARN #{n}", "BURGER BARN {city}"));

        m("PetroMax", "RETAIL", "FUEL", "5541", List.of("PETROMAX {n} {city}", "PETROMAX FUEL #{n}"));
        m("QuickFuel", "RETAIL", "FUEL", "5542", List.of("QUICKFUEL #{n}", "QKFUEL {city}"));
        m("Velocity Gas", "RETAIL", "FUEL", "5541", List.of("VELOCITY GAS {n}"));

        m("RideNow", "RETAIL", "TRNS", "4121", List.of("RIDENOW *TRIP {code}", "RIDENOW* PENDING"));
        m("Metro Transit Authority", "RETAIL", "TRNS", "4111", List.of("METRO TRANSIT FARE {n}", "MTA*RELOAD"));
        m("CityPark Parking", "RETAIL", "TRNS", "7523", List.of("CITYPARK LOT {n}"));

        m("Lumen Home Goods", "RETAIL", "SHOP", "5719", List.of("LUMEN HOME #{n}", "LUMENHOME.COM"));
        m("Threadline Apparel", "RETAIL", "SHOP", "5651", List.of("THREADLINE {city}", "THREADLINE ONLINE"));
        m("PageTurn Books", "RETAIL", "SHOP", "5942", List.of("PAGETURN BOOKS {n}"));
        m("GadgetHub Electronics", "RETAIL", "SHOP", "5732", List.of("GADGETHUB #{n}", "GADGETHUB.COM ORDER {code}"));

        // Multi-category merchants: the ambiguous cases. Ground truth comes from the purchase mix, not the merchant.
        multi("MegaMart Supercenter", "5311", List.of("MEGAMART SUPERCENTER #{n}", "MEGAMART {city}", "MGM*MEGAMART {n}"),
                Map.of("GROC", 60, "SHOP", 30, "HLTH", 10));
        multi("ShopSphere Online", "5399", List.of("SHOPSPHERE.COM*{code}", "SSPH MKTP {code}", "SHOPSPHERE DIGITAL"),
                Map.of("SHOP", 75, "EDUC", 10, "GROC", 15));
        multi("Cornerstone Pharmacy", "5912", List.of("CORNERSTONE PHARM #{n}", "CSTONE RX {city}"),
                Map.of("HLTH", 70, "GROC", 15, "SHOP", 15));

        m("Galaxy Cinemas", "RETAIL", "ENTR", "7832", List.of("GALAXY CINEMAS {city}", "GALAXYCIN ONLINE"));
        m("Arcadia Live Tickets", "RETAIL", "ENTR", "7922", List.of("ARCADIA LIVE*{code}"));
        m("Pinball Palace", "RETAIL", "ENTR", "7994", List.of("PINBALL PALACE {n}"));

        m("StreamFlix", "SUBSCRIPTION", "SUBS", "4899", List.of("STREAMFLIX.COM"));
        m("TuneWave Music", "SUBSCRIPTION", "SUBS", "5815", List.of("TUNEWAVE*PREMIUM"));
        m("CloudVault Storage", "SUBSCRIPTION", "SUBS", "5817", List.of("CLOUDVAULT STORAGE PLAN"));
        m("DailyLedger News", "SUBSCRIPTION", "SUBS", "5994", List.of("DAILYLEDGER DIGITAL"));
        m("FitPulse Gym", "SUBSCRIPTION", "HLTH", "7997", List.of("FITPULSE GYM MEMBERSHIP"));

        m("Wellspring Clinic", "RETAIL", "HLTH", "8011", List.of("WELLSPRING CLINIC {city}"));
        m("BrightSmile Dental", "RETAIL", "HLTH", "8021", List.of("BRIGHTSMILE DENTAL"));

        m("SkyBridge Airlines", "RETAIL", "TRVL", "4511", List.of("SKYBRIDGE AIR {code}"));
        m("Harborview Hotels", "RETAIL", "TRVL", "7011", List.of("HARBORVIEW HOTEL {city}"));
        m("TrailRent Car Rental", "RETAIL", "TRVL", "7512", List.of("TRAILRENT {city}"));

        m("Hope Harbor Charity", "RETAIL", "GIFT", "8398", List.of("HOPE HARBOR DONATION"));
        m("Petal and Stem Florist", "RETAIL", "GIFT", "5992", List.of("PETAL AND STEM {city}"));

        m("SkillForge Courses", "RETAIL", "EDUC", "8299", List.of("SKILLFORGE*COURSE {code}"));
        m("Campus Bookstore", "RETAIL", "EDUC", "5942", List.of("CAMPUS BOOKSTORE {n}"));

        m("Metro Power and Light", "UTILITY", "UTIL", "4900", List.of("METRO POWER LIGHT AUTOPAY"));
        m("AquaCity Water", "UTILITY", "UTIL", "4900", List.of("AQUACITY WATER BILL"));
        m("FiberLink Internet", "UTILITY", "UTIL", "4814", List.of("FIBERLINK INTERNET"));
        m("SignalWave Mobile", "UTILITY", "UTIL", "4814", List.of("SIGNALWAVE WIRELESS"));

        m("Shieldline Auto Insurance", "INSURER", "INSU", "6300", List.of("SHIELDLINE INS PREM"));
        m("Harbor Home Loans", "LENDER", "MORT", "6162", List.of("HARBOR HOME LOANS MTG PMT"));

        m("QuickPay P2P", "P2P", "PEER", "4829", List.of("QUICKPAY TO {person}"));
        m("ATM Network", "FINANCIAL", "CASH", "6011", List.of("ATM WITHDRAWAL #{n} {city}"));
        // The bank itself: fees, interest, internal transfers, card payments. Deliberately no default category.
        multiTyped("First Synthetic Bank", "FINANCIAL", null, List.of("FIRST SYNTHETIC BANK"),
                Map.of("FEES", 1, "INTR", 1, "XFER", 1, "CCPY", 1));

        String[] employerNames = {"Acme Robotics", "Bluewater Logistics", "Cedar Health Systems", "Delta Analytics",
                "Evergreen Schools", "Falcon Aerospace", "Granite Builders", "Horizon Retail Group", "Ironclad Security",
                "Juniper Software", "Keystone Foods", "Lighthouse Media", "Maple Insurance Co", "Nimbus Cloud Labs",
                "Orchard Farms", "Pioneer Energy", "Quarry Materials", "Riverbend Hospital", "Summit Consulting",
                "Tidewater Shipping", "Unity Telecom", "Vantage Motors", "Willow Pharma", "Xenon Biotech",
                "Yellowstone Outfitters"};
        for (String e : employerNames) {
            MerchantSpec spec = add(e, "EMPLOYER", "SALR", null,
                    List.of(e.toUpperCase() + " PAYROLL", "DIR DEP " + e.toUpperCase()), Map.of("SALR", 1));
            employers.add(spec);
        }
        String[] landlordNames = {"Oakridge", "Parkside", "Lakeview", "Hillcrest", "Riverside", "Brookfield", "Sunset",
                "Meadowbrook", "Stonegate", "Fairview", "Westwood", "Kingsley", "Ashford", "Bayside", "Crestwood",
                "Elmhurst", "Greystone", "Highland", "Ivywood", "Larkspur"};
        for (String l : landlordNames) {
            MerchantSpec spec = add(l + " Property Management", "LANDLORD", "RENT", "6513",
                    List.of(l.toUpperCase() + " PROPERTY MGMT RENT", l.toUpperCase() + " PROP MGMT ACH"),
                    Map.of("RENT", 1));
            landlords.add(spec);
        }
    }

    private void m(String name, String type, String category, String mcc, List<String> descriptors) {
        MerchantSpec spec = add(name, type, category, mcc, descriptors, Map.of(category, 1));
        merchantsByCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(spec);
    }

    private void multi(String name, String mcc, List<String> descriptors, Map<String, Integer> mix) {
        MerchantSpec spec = add(name, "RETAIL", null, mcc, descriptors, new LinkedHashMap<>(new java.util.TreeMap<>(mix)));
        for (String c : spec.truthMix().keySet()) {
            merchantsByCategory.computeIfAbsent(c, k -> new ArrayList<>()).add(spec);
        }
    }

    private void multiTyped(String name, String type, String mcc, List<String> descriptors, Map<String, Integer> mix) {
        add(name, type, null, mcc, descriptors, new LinkedHashMap<>(new java.util.TreeMap<>(mix)));
    }

    private MerchantSpec add(String name, String type, String category, String mcc, List<String> descriptors,
                             Map<String, Integer> mix) {
        String id = String.format("MER-%04d", ++merchantSeq);
        MerchantSpec spec = new MerchantSpec(id, name, type, category, mcc, descriptors, mix);
        merchants.add(spec);
        byName.put(name, spec);
        return spec;
    }

    public List<Merchant> merchantRows() {
        List<Merchant> rows = new ArrayList<>();
        for (MerchantSpec s : merchants) {
            rows.add(new Merchant(s.id(), s.name(), s.type(), s.defaultCategory(), s.mcc(), "ACTIVE"));
        }
        return rows;
    }

    public List<MerchantCategory> merchantCategoryRows() {
        List<MerchantCategory> rows = new ArrayList<>();
        for (MerchantSpec s : merchants) {
            for (String c : s.truthMix().keySet()) {
                rows.add(new MerchantCategory(s.id(), c, c.equals(s.defaultCategory())));
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ aliases
    private void buildAliases() {
        // Legacy codes from an older budgeting app. PETS / HOBBY / CHILDCARE intentionally have no mapping.
        alias("GROCERY", "GROC");
        alias("GROCERIES", "GROC");
        alias("DINING", "DINE");
        alias("RESTAURANTS", "DINE");
        alias("SUBSCRIPTIONS", "SUBS");
        alias("ENTERTAINMENT", "ENTR");
        alias("GAS", "FUEL");
        alias("TRANSPORT", "TRNS");
        alias("UTILITIES", "UTIL");
        alias("HEALTH", "HLTH");
    }

    private void alias(String a, String c) {
        aliases.add(new CategoryAlias(a, c));
    }

    // ------------------------------------------------------------------ rules
    private void buildRules() {
        // Priority 10: bank transaction-type rules
        rule(10, "TXN_TYPE", null, null, null, "INTEREST", null, null, "INTR");
        rule(10, "TXN_TYPE", null, null, null, "FEE", null, null, "FEES");
        rule(10, "TXN_TYPE", null, null, null, "ATM", null, null, "CASH");
        // Priority 20: descriptor patterns
        rule(20, "DESCRIPTOR_REGEX", null, "(?i)\\b(PAYROLL|DIR DEP)\\b", null, null, null, null, "SALR");
        rule(20, "DESCRIPTOR_REGEX", null, "(?i)(ONLINE TRANSFER|EXT XFER)", null, null, null, null, "XFER");
        rule(20, "DESCRIPTOR_REGEX", null, "(?i)(PAYMENT RECEIVED|CARD SVCS ONLINE PMT)", null, null, null, null, "CCPY");
        rule(20, "DESCRIPTOR_REGEX", null, "(?i)SHOPSPHERE PRIME", null, null, null, null, "SUBS");
        // Priority 30: single-category merchants
        for (MerchantSpec s : merchants) {
            if (s.defaultCategory() != null) {
                rule(30, "MERCHANT", s.id(), null, null, null, null, null, s.defaultCategory());
            }
        }
        // Priority 40: multi-category merchants disambiguated by amount band (imperfect on purpose)
        MerchantSpec mega = byName.get("MegaMart Supercenter");
        rule(40, "MERCHANT_AMOUNT", mega.id(), null, null, null, 0L, 2_500L, "HLTH");
        rule(40, "MERCHANT_AMOUNT", mega.id(), null, null, null, 2_501L, 15_000L, "GROC");
        rule(40, "MERCHANT_AMOUNT", mega.id(), null, null, null, 15_001L, null, "SHOP");
        MerchantSpec sphere = byName.get("ShopSphere Online");
        rule(40, "MERCHANT_AMOUNT", sphere.id(), null, null, null, null, null, "SHOP");
        MerchantSpec pharm = byName.get("Cornerstone Pharmacy");
        rule(40, "MERCHANT_AMOUNT", pharm.id(), null, null, null, null, null, "HLTH");
        // Priority 50: MCC fallbacks (used for merchants that are not in the master list)
        String[][] mcc = {{"5411", "GROC"}, {"5499", "GROC"}, {"5812", "DINE"}, {"5814", "DINE"}, {"5541", "FUEL"},
                {"5542", "FUEL"}, {"4121", "TRNS"}, {"4111", "TRNS"}, {"7523", "TRNS"}, {"5719", "SHOP"},
                {"5651", "SHOP"}, {"5942", "SHOP"}, {"5732", "SHOP"}, {"5311", "SHOP"}, {"5399", "SHOP"},
                {"7832", "ENTR"}, {"7922", "ENTR"}, {"7994", "ENTR"}, {"4899", "SUBS"}, {"5815", "SUBS"},
                {"5817", "SUBS"}, {"8011", "HLTH"}, {"8021", "HLTH"}, {"5912", "HLTH"}, {"7997", "HLTH"},
                {"4511", "TRVL"}, {"7011", "TRVL"}, {"7512", "TRVL"}, {"8398", "GIFT"}, {"5992", "GIFT"},
                {"8299", "EDUC"}, {"4900", "UTIL"}, {"4814", "UTIL"}, {"6300", "INSU"}, {"6011", "CASH"}};
        for (String[] r : mcc) {
            rule(50, "MCC", null, null, r[0], null, null, null, r[1]);
        }
    }

    private void rule(int priority, String type, String merchantId, String pattern, String mcc, String txnType,
                      Long min, Long max, String category) {
        String id = String.format("RUL-%04d", ++ruleSeq);
        rules.add(new CategoryRule(id, priority, type, merchantId, pattern, mcc, txnType, min, max, category,
                TAXONOMY_EFFECTIVE, null, "ACTIVE"));
    }
}
