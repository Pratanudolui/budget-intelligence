package com.yottaflex.budget.engine;

import com.yottaflex.budget.engine.RecurringDetector.Posting;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecurringDetectorTest {

    private static final LocalDate END = LocalDate.of(2026, 12, 31);

    private static Posting p(int month, int day, long amount) {
        return new Posting("T" + month + "-" + day, "USR-000001", "ACC-USR-000001-01", "MER-0001", "LANDLORD", "RENT",
                LocalDate.of(2026, month, day), -amount);
    }

    @Test
    void earlyPostingAcrossMonthBoundaryIsNotAMissedPayment() {
        List<Posting> ps = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            if (m != 5) ps.add(p(m, 1, 150_000));
        }
        ps.add(p(4, 30, 150_000)); // May rent posted on 30 April
        var series = new RecurringDetector().detect(ps, END);
        assertThat(series).hasSize(1);
        assertThat(series.get(0).missedDates()).isEmpty();
        assertThat(series.get(0).cadence()).isEqualTo("MONTHLY");
        assertThat(series.get(0).seriesId()).isEqualTo("REC-000001");
    }

    @Test
    void genuineSkipIsReported() {
        List<Posting> ps = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            if (m != 7) ps.add(p(m, 12, 1_549));
        }
        var series = new RecurringDetector().detect(ps, END);
        assertThat(series.get(0).missedDates()).containsExactly(LocalDate.of(2026, 7, 12));
    }

    @Test
    void cancelledSubscriptionIsEndedNotMissed() {
        List<Posting> ps = new ArrayList<>();
        for (int m = 1; m <= 6; m++) ps.add(p(m, 5, 999));
        var series = new RecurringDetector().detect(ps, END);
        assertThat(series.get(0).status()).isEqualTo("ENDED");
        assertThat(series.get(0).missedDates()).isEmpty();
    }

    @Test
    void irregularPurchasesAreNotRecurring() {
        List<Posting> ps = List.of(p(1, 3, 4_200), p(1, 9, 8_800), p(2, 20, 1_500), p(4, 2, 12_000), p(4, 5, 3_000));
        assertThat(new RecurringDetector().detect(ps, END)).isEmpty();
    }
}
