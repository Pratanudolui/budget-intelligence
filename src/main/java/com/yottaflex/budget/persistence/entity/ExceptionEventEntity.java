package com.yottaflex.budget.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "exception_event")
public class ExceptionEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long eventId;
    private Long exceptionPk;
    private String fromStatus;
    private String toStatus;
    private String actor;
    private String note;
    private Instant createdAt;

    protected ExceptionEventEntity() {
    }

    public ExceptionEventEntity(Long exceptionPk, String fromStatus, String toStatus, String actor, String note) {
        this.exceptionPk = exceptionPk;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
        this.note = note;
        this.createdAt = Instant.now();
    }

    public Long getEventId() { return eventId; }
    public Long getExceptionPk() { return exceptionPk; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getActor() { return actor; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}
