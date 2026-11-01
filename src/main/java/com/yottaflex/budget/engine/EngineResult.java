package com.yottaflex.budget.engine;

import com.yottaflex.budget.model.Insight;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything one pipeline run decides. Each decision carries the rule / method that produced it. */
public class EngineResult {

    public record Classification(String transactionId, String userId, String categoryCode, String method,
                                 String ruleId, double confidence, boolean transfer, String transferGroupId,
                                 boolean refund, String refundOfTransactionId, String recurringSeriesId,
                                 LocalDate localDate, String budgetMonth, long spendImpactCents, String reason) {
    }

    public record TransferLink(String groupId, String outTransactionId, String inTransactionId, String kind,
                               int lagDays, long amountCents) {
    }

    public record RefundLink(String refundTransactionId, String originalTransactionId, long amountCents,
                             int lagDays) {
    }

    public record DetectedSeries(String seriesId, String userId, String accountId, String merchantId, String kind,
                                 String cadence, long typicalAmountCents, LocalDate firstDate, LocalDate lastDate,
                                 int occurrences, String status, List<LocalDate> missedDates,
                                 LocalDate priceChangeDate, List<String> memberTransactionIds) {
    }

    public record BudgetComparison(String budgetId, String userId, String month, String suppliedCode,
                                   String resolvedCode, String resolution, long budgetCents, long actualCents,
                                   long varianceCents, double utilization, String status) {
    }

    public record ExceptionItem(String exceptionId, String exceptionType, String severity, String entityType,
                                String entityId, String userId, String detail) {
    }

    public final List<Classification> classifications = new ArrayList<>();
    public final List<TransferLink> transferLinks = new ArrayList<>();
    public final List<RefundLink> refundLinks = new ArrayList<>();
    public final List<DetectedSeries> recurringSeries = new ArrayList<>();
    public final List<BudgetComparison> budgetComparisons = new ArrayList<>();
    public final List<Insight> insights = new ArrayList<>();
    public final List<ExceptionItem> exceptions = new ArrayList<>();
    /** user|month|category -> spend cents (expense categories only, transfers excluded, refunds netted). */
    public final Map<String, Long> monthlyActuals = new LinkedHashMap<>();
}
