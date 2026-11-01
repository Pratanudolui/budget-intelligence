package com.yottaflex.budget.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

@Entity
@Table(name = "exception_record")
public class ExceptionRecordEntity {

    /** Allowed lifecycle transitions for the review queue. */
    public static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "OPEN", Set.of("IN_REVIEW", "DISMISSED"),
            "IN_REVIEW", Set.of("RESOLVED", "DISMISSED", "OPEN"),
            "RESOLVED", Set.of(),
            "DISMISSED", Set.of());

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String runId;
    private String exceptionId;
    private String exceptionType;
    private String severity;
    private String entityType;
    private String entityId;
    private String userId;
    private String detail;
    private String status;
    private String assignedTo;
    private Instant updatedAt;

    protected ExceptionRecordEntity() {
    }

    public boolean canTransitionTo(String target) {
        return TRANSITIONS.getOrDefault(status, Set.of()).contains(target);
    }

    public void transition(String target, String actor) {
        this.status = target;
        this.updatedAt = Instant.now();
        if ("IN_REVIEW".equals(target)) {
            this.assignedTo = actor;
        }
    }

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public String getExceptionId() { return exceptionId; }
    public String getExceptionType() { return exceptionType; }
    public String getSeverity() { return severity; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public String getUserId() { return userId; }
    public String getDetail() { return detail; }
    public String getStatus() { return status; }
    public String getAssignedTo() { return assignedTo; }
    public Instant getUpdatedAt() { return updatedAt; }
}
