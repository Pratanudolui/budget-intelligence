package com.yottaflex.budget.model;

import java.time.LocalDate;

public record Category(String categoryCode, String name, String parentCode, String kind,
                       boolean essential, LocalDate effectiveFrom, String status) {

    public boolean isExpense() {
        return "EXPENSE".equals(kind);
    }

    public boolean isTransfer() {
        return "TRANSFER".equals(kind);
    }
}
