package com.yottaflex.budget.persistence.repository;

import com.yottaflex.budget.persistence.entity.ExceptionRecordEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ExceptionRecordRepository extends JpaRepository<ExceptionRecordEntity, Long> {

    Optional<ExceptionRecordEntity> findByRunIdAndExceptionId(String runId, String exceptionId);

    @Query("select e from ExceptionRecordEntity e where e.runId = :runId "
            + "and (:status is null or e.status = :status) and (:type is null or e.exceptionType = :type) "
            + "and (:userId is null or e.userId = :userId) order by e.id")
    Page<ExceptionRecordEntity> search(@Param("runId") String runId, @Param("status") String status,
                                       @Param("type") String type, @Param("userId") String userId, Pageable page);
}
