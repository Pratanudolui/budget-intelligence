package com.yottaflex.budget.engine;

import com.yottaflex.budget.engine.EngineResult.DetectedSeries;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Detects recurring series per (account, merchant) and checks each expected due date against the observed
 * postings with a tolerance window, so a payment that posts a few days early/late (crossing a month boundary)
 * is matched to its due date instead of being reported as a skip.
 */
public class RecurringDetector {

    public static final int MIN_OCCURRENCES = 3;
    public static final int MONTHLY_TOLERANCE_DAYS = 7;
    public static final int BIWEEKLY_TOLERANCE_DAYS = 4;

    /** One candidate posting. */
    public record Posting(String transactionId, String userId, String accountId, String merchantId,
                          String merchantType, String categoryCode, LocalDate date, long amountCents) {
    }

    private record Candidate(String cadence, int period) {
    }

    public List<DetectedSeries> detect(List<Posting> postings, LocalDate dataEnd) {
        Map<String, List<Posting>> groups = new TreeMap<>();
        for (Posting p : postings) {
            if (p.merchantId() != null) {
                groups.computeIfAbsent(p.accountId() + "|" + p.merchantId(), k -> new ArrayList<>()).add(p);
            }
        }
        List<DetectedSeries> found = new ArrayList<>();
        for (List<Posting> group : groups.values()) {
            if (group.size() < MIN_OCCURRENCES) {
                continue;
            }
            group.sort(Comparator.comparing(Posting::date).thenComparing(Posting::transactionId));
            DetectedSeries whole = tryBuild(group, dataEnd, 0.30);
            if (whole != null) {
                found.add(whole);
                continue;
            }
            // Mixed group (e.g. a membership fee plus ad-hoc purchases at the same merchant): try exact-amount clusters.
            Map<Long, List<Posting>> byAmount = new TreeMap<>();
            for (Posting p : group) {
                byAmount.computeIfAbsent(p.amountCents(), k -> new ArrayList<>()).add(p);
            }
            for (List<Posting> cluster : byAmount.values()) {
                if (cluster.size() >= MIN_OCCURRENCES) {
                    DetectedSeries s = tryBuild(cluster, dataEnd, 0.0);
                    if (s != null) {
                        found.add(s);
                    }
                }
            }
        }
        found.sort(Comparator.comparing(DetectedSeries::userId).thenComparing(DetectedSeries::accountId)
                .thenComparing(DetectedSeries::merchantId).thenComparing(DetectedSeries::firstDate));
        List<DetectedSeries> numbered = new ArrayList<>();
        int seq = 0;
        for (DetectedSeries s : found) {
            numbered.add(new DetectedSeries(String.format("REC-%06d", ++seq), s.userId(), s.accountId(), s.merchantId(),
                    s.kind(), s.cadence(), s.typicalAmountCents(), s.firstDate(), s.lastDate(), s.occurrences(),
                    s.status(), s.missedDates(), s.priceChangeDate(), s.memberTransactionIds()));
        }
        return numbered;
    }

    private DetectedSeries tryBuild(List<Posting> ps, LocalDate dataEnd, double maxCv) {
        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < ps.size(); i++) {
            gaps.add((int) ChronoUnit.DAYS.between(ps.get(i - 1).date(), ps.get(i).date()));
        }
        List<Integer> sorted = new ArrayList<>(gaps);
        Collections.sort(sorted);
        int median = sorted.get(sorted.size() / 2);
        Candidate c = median >= 26 && median <= 34 ? new Candidate("MONTHLY", 30)
                : median >= 13 && median <= 15 ? new Candidate("BIWEEKLY", 14)
                : median >= 6 && median <= 8 ? new Candidate("WEEKLY", 7) : null;
        if (c == null) {
            return null;
        }
        int regular = 0;
        for (int g : gaps) {
            double tol = c.period() * 0.25;
            if (Math.abs(g - c.period()) <= tol || Math.abs(g - 2 * c.period()) <= tol) {
                regular++;
            }
        }
        if (regular < Math.ceil(gaps.size() * 0.75)) {
            return null;
        }
        long[] amounts = ps.stream().mapToLong(p -> Math.abs(p.amountCents())).sorted().toArray();
        long typical = amounts[amounts.length / 2];
        if (maxCv > 0) {
            double mean = Arrays.stream(amounts).average().orElse(0);
            double var = Arrays.stream(amounts).mapToDouble(a -> (a - mean) * (a - mean)).average().orElse(0);
            if (mean == 0 || Math.sqrt(var) / mean > maxCv) {
                return null;
            }
        }
        LocalDate first = ps.get(0).date();
        LocalDate last = ps.get(ps.size() - 1).date();
        int tolerance = "MONTHLY".equals(c.cadence()) ? MONTHLY_TOLERANCE_DAYS : BIWEEKLY_TOLERANCE_DAYS;
        boolean ended = last.plusDays(c.period() + tolerance).isBefore(dataEnd);
        LocalDate horizon = ended ? last : dataEnd.minusDays(tolerance);
        List<LocalDate> expected = expectedDates(ps, c, first, horizon);
        List<LocalDate> missed = matchMissed(ps, expected, tolerance);
        Posting p0 = ps.get(0);
        return new DetectedSeries(null, p0.userId(), p0.accountId(), p0.merchantId(),
                kindOf(p0.merchantType(), p0.categoryCode()), c.cadence(), typical, first, last, ps.size(),
                ended ? "ENDED" : "ACTIVE", missed, priceChange(ps, kindOf(p0.merchantType(), p0.categoryCode())),
                ps.stream().map(Posting::transactionId).toList());
    }

    private List<LocalDate> expectedDates(List<Posting> ps, Candidate c, LocalDate first, LocalDate horizon) {
        List<LocalDate> out = new ArrayList<>();
        if ("MONTHLY".equals(c.cadence())) {
            int[] days = ps.stream().mapToInt(p -> p.date().getDayOfMonth()).sorted().toArray();
            int anchor = days[days.length / 2];
            for (YearMonth ym = YearMonth.from(first); !ym.atDay(1).isAfter(horizon); ym = ym.plusMonths(1)) {
                LocalDate d = ym.atDay(Math.min(anchor, ym.lengthOfMonth()));
                if (!d.isBefore(first.minusDays(MONTHLY_TOLERANCE_DAYS)) && !d.isAfter(horizon)) {
                    out.add(d);
                }
            }
        } else {
            for (LocalDate d = first; !d.isAfter(horizon); d = d.plusDays(c.period())) {
                out.add(d);
            }
        }
        return out;
    }

    /** Greedy one-to-one matching of expected dates to the nearest unused posting within tolerance. */
    private List<LocalDate> matchMissed(List<Posting> ps, List<LocalDate> expected, int tolerance) {
        boolean[] used = new boolean[ps.size()];
        List<LocalDate> missed = new ArrayList<>();
        for (LocalDate e : expected) {
            int best = -1;
            long bestDist = Long.MAX_VALUE;
            for (int i = 0; i < ps.size(); i++) {
                if (used[i]) {
                    continue;
                }
                long dist = Math.abs(ChronoUnit.DAYS.between(e, ps.get(i).date()));
                if (dist <= tolerance && dist < bestDist) {
                    best = i;
                    bestDist = dist;
                }
            }
            if (best >= 0) {
                used[best] = true;
            } else {
                missed.add(e);
            }
        }
        return missed;
    }

    private LocalDate priceChange(List<Posting> ps, String kind) {
        if (!"SUBSCRIPTION".equals(kind)) {
            return null;
        }
        long base = ps.get(0).amountCents();
        for (int i = 1; i < ps.size(); i++) {
            if (ps.get(i).amountCents() != base) {
                long next = ps.get(i).amountCents();
                boolean persists = ps.subList(i, ps.size()).stream().allMatch(p -> p.amountCents() == next);
                return persists ? ps.get(i).date() : null;
            }
        }
        return null;
    }

    static String kindOf(String merchantType, String categoryCode) {
        if (merchantType != null) {
            switch (merchantType) {
                case "EMPLOYER":
                    return "SALARY";
                case "LANDLORD":
                    return "RENT";
                case "LENDER":
                    return "MORTGAGE";
                case "UTILITY":
                    return "UTILITY";
                case "INSURER":
                    return "INSURANCE";
                case "SUBSCRIPTION":
                    return "SUBSCRIPTION";
                default:
                    break;
            }
        }
        return "SUBS".equals(categoryCode) ? "SUBSCRIPTION" : "OTHER";
    }
}
