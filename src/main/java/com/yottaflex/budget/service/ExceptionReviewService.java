package com.yottaflex.budget.service;

import com.yottaflex.budget.persistence.entity.ExceptionEventEntity;
import com.yottaflex.budget.persistence.entity.ExceptionRecordEntity;
import com.yottaflex.budget.persistence.repository.ExceptionEventRepository;
import com.yottaflex.budget.persistence.repository.ExceptionRecordRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Review queue for the risk/compliance reviewer. Status changes are validated and logged as events. */
@Service
public class ExceptionReviewService {

    private final ExceptionRecordRepository exceptions;
    private final ExceptionEventRepository events;
    private final ReportService reports;

    public ExceptionReviewService(ExceptionRecordRepository exceptions, ExceptionEventRepository events,
                                  ReportService reports) {
        this.exceptions = exceptions;
        this.events = events;
        this.reports = reports;
    }

    public Page<ExceptionRecordEntity> search(String runId, String status, String type, String userId, int page, int size) {
        return exceptions.search(reports.resolveRun(runId), status, type, userId, PageRequest.of(page, size));
    }

    @Transactional
    public ExceptionRecordEntity transition(String runId, String exceptionId, String target, String actor, String note) {
        ExceptionRecordEntity e = find(runId, exceptionId);
        if (!ExceptionRecordEntity.TRANSITIONS.containsKey(target)) {
            throw new IllegalArgumentException("Unknown status '" + target + "'; allowed: "
                    + ExceptionRecordEntity.TRANSITIONS.keySet());
        }
        if (!e.canTransitionTo(target)) {
            throw new ConflictException("Illegal transition " + e.getStatus() + " -> " + target + " for " + exceptionId
                    + "; allowed from " + e.getStatus() + ": " + ExceptionRecordEntity.TRANSITIONS.get(e.getStatus()));
        }
        String from = e.getStatus();
        e.transition(target, actor);
        events.save(new ExceptionEventEntity(e.getId(), from, target, actor, note));
        return e;
    }

    public List<ExceptionEventEntity> history(String runId, String exceptionId) {
        return events.findByExceptionPkOrderByEventId(find(runId, exceptionId).getId());
    }

    private ExceptionRecordEntity find(String runId, String exceptionId) {
        String run = reports.resolveRun(runId);
        return exceptions.findByRunIdAndExceptionId(run, exceptionId)
                .orElseThrow(() -> new NotFoundException("Exception " + exceptionId + " not found in " + run));
    }
}
