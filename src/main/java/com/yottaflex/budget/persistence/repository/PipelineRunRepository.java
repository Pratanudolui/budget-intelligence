package com.yottaflex.budget.persistence.repository;

import com.yottaflex.budget.persistence.entity.PipelineRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PipelineRunRepository extends JpaRepository<PipelineRunEntity, String> {
    Optional<PipelineRunEntity> findFirstByStatusOrderByStartedAtDesc(String status);

    List<PipelineRunEntity> findAllByOrderByStartedAtDesc();
}
