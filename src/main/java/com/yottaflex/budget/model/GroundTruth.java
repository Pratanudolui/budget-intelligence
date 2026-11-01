package com.yottaflex.budget.model;

import java.util.ArrayList;
import java.util.List;

/** Labels produced by the generator from documented rules. Used only by evaluation, never as engine input. */
public class GroundTruth {
    public final List<TransactionLabel> transactionLabels = new ArrayList<>();
    public final List<RecurringSeriesTruth> recurringSeries = new ArrayList<>();
    public final List<BudgetLabel> budgetLabels = new ArrayList<>();
    public final List<MonthlyActual> monthlyActuals = new ArrayList<>();
    public final List<Insight> expectedInsights = new ArrayList<>();
}
