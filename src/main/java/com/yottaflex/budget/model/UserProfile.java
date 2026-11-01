package com.yottaflex.budget.model;

import java.time.LocalDate;

public record UserProfile(String userId, String displayName, int householdSize, String timezone,
                          String incomeBand, long annualIncomeCents, String city, LocalDate createdOn,
                          String status) {
}
