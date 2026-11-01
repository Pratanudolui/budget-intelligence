package com.yottaflex.budget.model;

import java.time.LocalDate;

/** categoryCode is stored exactly as supplied; it may not match the taxonomy (labelled edge case). */
public record Budget(String budgetId, String userId, String budgetMonth, String categoryCode,
                     long amountCents, String currency, LocalDate effectiveFrom, String status) {
}
