package com.yottaflex.budget.service;

import com.yottaflex.budget.persistence.entity.BudgetEntity;
import com.yottaflex.budget.persistence.entity.BudgetEventEntity;
import com.yottaflex.budget.persistence.repository.BudgetEventRepository;
import com.yottaflex.budget.persistence.repository.BudgetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/** Budget amount changes keep the previous value in budget_event, so history is never lost. */
@Service
public class BudgetService {

    private final BudgetRepository budgets;
    private final BudgetEventRepository events;

    public BudgetService(BudgetRepository budgets, BudgetEventRepository events) {
        this.budgets = budgets;
        this.events = events;
    }

    @Transactional
    public BudgetEntity changeAmount(String budgetId, BigDecimal amount, String actor, String reason) {
        BudgetEntity b = budgets.findById(budgetId).orElseThrow(() -> new NotFoundException("Budget " + budgetId + " not found"));
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Budget amount must be positive");
        }
        events.save(new BudgetEventEntity(budgetId, "AMOUNT_CHANGED", b.getBudgetAmount(), amount, actor, reason));
        b.setBudgetAmount(amount);
        return b;
    }

    public List<BudgetEventEntity> history(String budgetId) {
        if (!budgets.existsById(budgetId)) {
            throw new NotFoundException("Budget " + budgetId + " not found");
        }
        return events.findByBudgetIdOrderByEventId(budgetId);
    }
}
