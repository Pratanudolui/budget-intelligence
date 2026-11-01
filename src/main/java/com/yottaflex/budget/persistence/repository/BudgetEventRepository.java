package com.yottaflex.budget.persistence.repository;

import com.yottaflex.budget.persistence.entity.BudgetEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BudgetEventRepository extends JpaRepository<BudgetEventEntity, Long> {
    List<BudgetEventEntity> findByBudgetIdOrderByEventId(String budgetId);
}
