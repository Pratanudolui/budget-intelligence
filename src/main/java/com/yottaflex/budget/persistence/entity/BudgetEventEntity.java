package com.yottaflex.budget.persistence.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "budget_event")
public class BudgetEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long eventId;
    private String budgetId;
    private String eventType;
    private BigDecimal oldAmount;
    private BigDecimal newAmount;
    private String actor;
    private String reason;
    private Instant createdAt;

    protected BudgetEventEntity() {
    }

    public BudgetEventEntity(String budgetId, String eventType, BigDecimal oldAmount, BigDecimal newAmount,
                             String actor, String reason) {
        this.budgetId = budgetId;
        this.eventType = eventType;
        this.oldAmount = oldAmount;
        this.newAmount = newAmount;
        this.actor = actor;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public Long getEventId() { return eventId; }
    public String getBudgetId() { return budgetId; }
    public String getEventType() { return eventType; }
    public BigDecimal getOldAmount() { return oldAmount; }
    public BigDecimal getNewAmount() { return newAmount; }
    public String getActor() { return actor; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
