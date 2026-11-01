package com.yottaflex.budget.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "pipeline_run")
public class PipelineRunEntity {

    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    @Id
    @Column(name = "run_id")
    private String runId;
    private String status;
    private String triggeredBy;
    private Instant startedAt;
    private Instant finishedAt;
    private Integer transactions;
    private Integer exceptions;
    private Integer insights;
    private Integer recurringSeries;
    private String metricsJson;
    private String errorMessage;

    protected PipelineRunEntity() {
    }

    public PipelineRunEntity(String runId, String triggeredBy) {
        this.runId = runId;
        this.triggeredBy = triggeredBy;
        this.status = RUNNING;
        this.startedAt = Instant.now();
    }

    public void complete(int transactions, int exceptions, int insights, int recurringSeries, String metricsJson) {
        this.status = COMPLETED;
        this.finishedAt = Instant.now();
        this.transactions = transactions;
        this.exceptions = exceptions;
        this.insights = insights;
        this.recurringSeries = recurringSeries;
        this.metricsJson = metricsJson;
    }

    public void fail(String message) {
        this.status = FAILED;
        this.finishedAt = Instant.now();
        this.errorMessage = message == null ? null : message.substring(0, Math.min(1000, message.length()));
    }

    public String getRunId() { return runId; }
    public String getStatus() { return status; }
    public String getTriggeredBy() { return triggeredBy; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Integer getTransactions() { return transactions; }
    public Integer getExceptions() { return exceptions; }
    public Integer getInsights() { return insights; }
    public Integer getRecurringSeries() { return recurringSeries; }
    public String getMetricsJson() { return metricsJson; }
    public String getErrorMessage() { return errorMessage; }
}
