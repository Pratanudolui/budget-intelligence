package com.yottaflex.budget.web;

import com.yottaflex.budget.persistence.entity.BudgetEntity;
import com.yottaflex.budget.persistence.entity.BudgetEventEntity;
import com.yottaflex.budget.service.BudgetService;
import com.yottaflex.budget.web.dto.BudgetChangeRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/budgets/{budgetId}")
public class BudgetController {

    private final BudgetService budgets;

    public BudgetController(BudgetService budgets) {
        this.budgets = budgets;
    }

    @PutMapping("/amount")
    public BudgetEntity change(@PathVariable String budgetId, @Valid @RequestBody BudgetChangeRequest request,
                               @RequestHeader(value = "X-Actor", defaultValue = "customer") String actor) {
        return budgets.changeAmount(budgetId, request.amount(), actor, request.reason());
    }

    @GetMapping("/history")
    public List<BudgetEventEntity> history(@PathVariable String budgetId) {
        return budgets.history(budgetId);
    }
}
