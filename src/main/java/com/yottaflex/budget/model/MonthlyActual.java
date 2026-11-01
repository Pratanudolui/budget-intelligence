package com.yottaflex.budget.model;

public record MonthlyActual(String userId, String month, String categoryCode, long spendCents, int txnCount) {
}
