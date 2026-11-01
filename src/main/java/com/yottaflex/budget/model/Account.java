package com.yottaflex.budget.model;

import java.time.LocalDate;

public record Account(String accountId, String userId, String accountType, LocalDate openedOn,
                      LocalDate closedOn, String status, String currency, long openingBalanceCents,
                      long closingBalanceCents) {
}
