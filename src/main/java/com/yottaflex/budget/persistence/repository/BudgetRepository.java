package com.yottaflex.budget.persistence.repository;

import com.yottaflex.budget.persistence.entity.BudgetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetRepository extends JpaRepository<BudgetEntity, String> {
}
