package com.yottaflex.budget.model;

import java.time.LocalDate;

/**
 * Data-driven categorization rule. matchType is one of TXN_TYPE, DESCRIPTOR_REGEX, MERCHANT,
 * MERCHANT_AMOUNT, MCC. Amount bounds apply to the absolute amount in cents and are inclusive.
 */
public record CategoryRule(String ruleId, int priority, String matchType, String merchantId,
                           String pattern, String mcc, String txnType, Long minAbsCents, Long maxAbsCents,
                           String categoryCode, LocalDate effectiveFrom, LocalDate effectiveTo,
                           String status) {
}
