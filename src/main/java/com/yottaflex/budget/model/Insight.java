package com.yottaflex.budget.model;

public record Insight(String insightId, String userId, String month, String insightType, String categoryCode,
                      long actualCents, long referenceCents, String severity, String evidence) {
}
