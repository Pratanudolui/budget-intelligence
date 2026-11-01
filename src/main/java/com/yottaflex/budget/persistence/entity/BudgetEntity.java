package com.yottaflex.budget.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "budget")
public class BudgetEntity {

    @Id
    @Column(name = "budget_id")
    private String budgetId;
    private String userId;
    private String budgetMonth;
    private String categoryCode;
    private BigDecimal budgetAmount;
    private String currency;
    private LocalDate effectiveFrom;
    private String status;

    public String getBudgetId() { return budgetId; }
    public String getUserId() { return userId; }
    public String getBudgetMonth() { return budgetMonth; }
    public String getCategoryCode() { return categoryCode; }
    public BigDecimal getBudgetAmount() { return budgetAmount; }
    public String getCurrency() { return currency; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public String getStatus() { return status; }

    public void setBudgetAmount(BigDecimal budgetAmount) { this.budgetAmount = budgetAmount; }
}
