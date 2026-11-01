package com.yottaflex.budget.model;

import java.time.LocalDate;

public record RecurringSeriesTruth(String seriesId, String userId, String accountId, String merchantId,
                                   String kind, String cadence, long expectedAmountCents, LocalDate startDate,
                                   LocalDate endDate, String status, int occurrences, String missedDates,
                                   String shiftedDates, String priceChangeDate, String issueType) {
}
