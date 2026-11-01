package com.yottaflex.budget.model;

import java.time.LocalDate;

/** Ground-truth label for one transaction. Never read by the categorization engine. */
public record TransactionLabel(String transactionId, String trueCategoryCode, boolean transfer,
                               String transferGroupId, boolean refund, String refundOfTransactionId,
                               String recurringSeriesId, LocalDate localDate, String budgetMonth,
                               String issueType, String expectedDecision) {
}
