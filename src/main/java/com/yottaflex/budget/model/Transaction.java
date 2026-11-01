package com.yottaflex.budget.model;

import java.time.Instant;

public record Transaction(String transactionId, String accountId, String merchantId, Instant postedAtUtc,
                          long amountCents, String currency, String descriptor, String txnType, String mcc,
                          long balanceAfterCents) {
}
