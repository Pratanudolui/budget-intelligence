package com.yottaflex.budget.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "user_profile")
public class UserProfileEntity {

    @Id
    @Column(name = "user_id")
    private String userId;
    private String displayName;
    private int householdSize;
    private String timezone;
    private String incomeBand;
    private BigDecimal annualIncome;
    private String city;
    private LocalDate createdOn;
    private String status;

    public String getUserId() { return userId; }
    public String getDisplayName() { return displayName; }
    public int getHouseholdSize() { return householdSize; }
    public String getTimezone() { return timezone; }
    public String getIncomeBand() { return incomeBand; }
    public BigDecimal getAnnualIncome() { return annualIncome; }
    public String getCity() { return city; }
    public LocalDate getCreatedOn() { return createdOn; }
    public String getStatus() { return status; }
}
