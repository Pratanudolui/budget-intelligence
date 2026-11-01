package com.yottaflex.budget.persistence.repository;

import com.yottaflex.budget.persistence.entity.ExceptionEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExceptionEventRepository extends JpaRepository<ExceptionEventEntity, Long> {
    List<ExceptionEventEntity> findByExceptionPkOrderByEventId(Long exceptionPk);
}
