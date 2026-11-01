package com.yottaflex.budget.model;

public record BudgetLabel(String budgetId, String suppliedCategoryCode, String expectedCategoryCode,
                          String issueType, String expectedDecision) {
}
