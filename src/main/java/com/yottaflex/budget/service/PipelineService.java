package com.yottaflex.budget.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yottaflex.budget.engine.BudgetIntelligenceEngine;
import com.yottaflex.budget.engine.EngineResult;
import com.yottaflex.budget.engine.EngineResult.*;
import com.yottaflex.budget.evaluation.Evaluator;
import com.yottaflex.budget.model.Dataset;
import com.yottaflex.budget.model.GroundTruth;
import com.yottaflex.budget.model.Insight;
import com.yottaflex.budget.persistence.entity.PipelineRunEntity;
import com.yottaflex.budget.persistence.repository.PipelineRunRepository;
import com.yottaflex.budget.util.DatasetIO;
import com.yottaflex.budget.util.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Runs the decision pipeline against the database and appends its outputs under a new run_id.
 * Previous runs are never modified, so every decision stays traceable to the run that made it.
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final DatasetReader reader;
    private final PipelineRunRepository runs;
    private final DataSource dataSource;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final DatasetLoaderService loader;

    public PipelineService(DatasetReader reader, PipelineRunRepository runs, DataSource dataSource,
                           TransactionTemplate tx, ObjectMapper mapper, DatasetLoaderService loader) {
        this.reader = reader;
        this.runs = runs;
        this.dataSource = dataSource;
        this.tx = tx;
        this.mapper = mapper;
        this.loader = loader;
    }

    public PipelineRunEntity run(String actor) {
        String runId = String.format("RUN-%06d", runs.count() + 1);
        PipelineRunEntity run = runs.save(new PipelineRunEntity(runId, actor));
        try {
            long start = System.currentTimeMillis();
            Dataset dataset = reader.read();
            if (dataset.transactions.isEmpty()) {
                throw new ConflictException("No transactions loaded; call POST /api/admin/datasets/load first");
            }
            EngineResult result = new BudgetIntelligenceEngine().run(dataset);
            log.info("{}: engine finished {} transactions in {} ms", runId, dataset.transactions.size(),
                    System.currentTimeMillis() - start);
            tx.executeWithoutResult(status -> persist(runId, result));
            String metrics = evaluateIfGroundTruthAvailable(dataset, result);
            run.complete(result.classifications.size(), result.exceptions.size(), result.insights.size(),
                    result.recurringSeries.size(), metrics);
            log.info("{}: completed in {} ms", runId, System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            log.error("{} failed", runId, e);
            run.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
            runs.save(run);
            throw e;
        }
        return runs.save(run);
    }

    private String evaluateIfGroundTruthAvailable(Dataset dataset, EngineResult result) {
        Path dir = loader.latestDatasetPath();
        if (dir == null || !Files.exists(dir.resolve("ground_truth").resolve(DatasetIO.GT_TXN))) {
            return null;
        }
        try {
            GroundTruth truth = DatasetIO.readGroundTruth(dir);
            Map<String, Object> report = new Evaluator().evaluate(dataset, truth, result);
            return mapper.writeValueAsString(report);
        } catch (Exception e) {
            log.warn("Evaluation skipped: {}", e.getMessage());
            return null;
        }
    }

    private void persist(String runId, EngineResult r) {
        Connection c = DataSourceUtils.getConnection(dataSource);
        try {
            try (PgCopy.RowWriter w = new PgCopy.RowWriter(c, "transaction_classification",
                    "run_id,transaction_id,user_id,category_code,method,rule_id,confidence,is_transfer,transfer_group_id,"
                            + "is_refund,refund_of_transaction_id,recurring_series_id,local_date,budget_month,spend_impact,reason")) {
                for (Classification x : r.classifications) {
                    w.row(runId, x.transactionId(), x.userId(), x.categoryCode(), x.method(), x.ruleId(), x.confidence(),
                            x.transfer(), x.transferGroupId(), x.refund(), x.refundOfTransactionId(),
                            x.recurringSeriesId(), x.localDate(), x.budgetMonth(), Money.format(x.spendImpactCents()),
                            x.reason());
                }
            }
            try (PgCopy.RowWriter w = new PgCopy.RowWriter(c, "transfer_link",
                    "run_id,group_id,out_transaction_id,in_transaction_id,kind,lag_days,amount")) {
                for (TransferLink x : r.transferLinks) {
                    w.row(runId, x.groupId(), x.outTransactionId(), x.inTransactionId(), x.kind(), x.lagDays(),
                            Money.format(x.amountCents()));
                }
            }
            try (PgCopy.RowWriter w = new PgCopy.RowWriter(c, "refund_link",
                    "run_id,refund_transaction_id,original_transaction_id,amount,lag_days")) {
                for (RefundLink x : r.refundLinks) {
                    w.row(runId, x.refundTransactionId(), x.originalTransactionId(), Money.format(x.amountCents()),
                            x.lagDays());
                }
            }
            try (PgCopy.RowWriter w = new PgCopy.RowWriter(c, "recurring_series",
                    "run_id,series_id,user_id,account_id,merchant_id,kind,cadence,typical_amount,first_date,last_date,"
                            + "occurrences,status,missed_dates,price_change_date")) {
                for (DetectedSeries s : r.recurringSeries) {
                    StringJoiner missed = new StringJoiner(";");
                    s.missedDates().forEach(m -> missed.add(m.toString()));
                    w.row(runId, s.seriesId(), s.userId(), s.accountId(), s.merchantId(), s.kind(), s.cadence(),
                            Money.format(s.typicalAmountCents()), s.firstDate(), s.lastDate(), s.occurrences(), s.status(),
                            missed.length() == 0 ? null : missed.toString(), s.priceChangeDate());
                }
            }
            try (PgCopy.RowWriter w = new PgCopy.RowWriter(c, "recurring_series_member", "run_id,series_id,transaction_id")) {
                for (DetectedSeries s : r.recurringSeries) {
                    for (String id : s.memberTransactionIds()) {
                        w.row(runId, s.seriesId(), id);
                    }
                }
            }
            try (PgCopy.RowWriter w = new PgCopy.RowWriter(c, "budget_comparison",
                    "run_id,budget_id,user_id,budget_month,supplied_code,resolved_code,resolution,budget_amount,"
                            + "actual_amount,variance,utilization,status")) {
                for (BudgetComparison b : r.budgetComparisons) {
                    w.row(runId, b.budgetId(), b.userId(), b.month(), b.suppliedCode(), b.resolvedCode(), b.resolution(),
                            Money.format(b.budgetCents()), Money.format(b.actualCents()), Money.format(b.varianceCents()),
                            b.utilization(), b.status());
                }
            }
            try (PgCopy.RowWriter w = new PgCopy.RowWriter(c, "insight",
                    "run_id,insight_id,user_id,month,insight_type,category_code,actual_amount,reference_amount,severity,evidence")) {
                for (Insight i : r.insights) {
                    w.row(runId, i.insightId(), i.userId(), i.month(), i.insightType(), i.categoryCode(),
                            Money.format(i.actualCents()), Money.format(i.referenceCents()), i.severity(), i.evidence());
                }
            }
            String now = Instant.now().toString();
            try (PgCopy.RowWriter w = new PgCopy.RowWriter(c, "exception_record",
                    "run_id,exception_id,exception_type,severity,entity_type,entity_id,user_id,detail,status,updated_at")) {
                for (ExceptionItem e : r.exceptions) {
                    w.row(runId, e.exceptionId(), e.exceptionType(), e.severity(), e.entityType(), e.entityId(),
                            e.userId(), e.detail(), "OPEN", now);
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Failed persisting run " + runId + ": " + e.getMessage(), e);
        }
    }
}
