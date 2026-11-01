package com.yottaflex.budget.persistence.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "account")
public class AccountEntity {

    @Id
    @Column(name = "account_id")
    private String accountId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserProfileEntity user;

    private String accountType;
    private LocalDate openedOn;
    private LocalDate closedOn;
    private String status;
    private String currency;
    private BigDecimal openingBalance;
    private BigDecimal closingBalance;

    public String getAccountId() { return accountId; }
    public UserProfileEntity getUser() { return user; }
    public String getAccountType() { return accountType; }
    public LocalDate getOpenedOn() { return openedOn; }
    public LocalDate getClosedOn() { return closedOn; }
    public String getStatus() { return status; }
    public String getCurrency() { return currency; }
    public BigDecimal getOpeningBalance() { return openingBalance; }
    public BigDecimal getClosingBalance() { return closingBalance; }
}
