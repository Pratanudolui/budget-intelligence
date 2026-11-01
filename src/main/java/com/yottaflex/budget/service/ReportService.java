package com.yottaflex.budget.service;

import com.yottaflex.budget.persistence.entity.PipelineRunEntity;
import com.yottaflex.budget.persistence.repository.PipelineRunRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read models for the customer and operations views. All queries are scoped to one pipeline run. */
@Service
public class ReportService {

    private final JdbcTemplate jdbc;
    private final PipelineRunRepository runs;

    public ReportService(JdbcTemplate jdbc, PipelineRunRepository runs) {
        this.jdbc = jdbc;
        this.runs = runs;
    }

    /** Explicit run id if given, otherwise the latest completed run. */
    public String resolveRun(String runId) {
        if (runId != null && !runId.isBlank()) {
            PipelineRunEntity run = runs.findById(runId).orElseThrow(() -> new NotFoundException("Run " + runId + " not found"));
            if (!PipelineRunEntity.COMPLETED.equals(run.getStatus())) {
                throw new ConflictException("Run " + runId + " is " + run.getStatus());
            }
            return runId;
        }
        return runs.findFirstByStatusOrderByStartedAtDesc(PipelineRunEntity.COMPLETED)
                .map(PipelineRunEntity::getRunId)
                .orElseThrow(() -> new NotFoundException("No completed pipeline run; call POST /api/pipeline/runs"));
    }

    public void requireUser(String userId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM user_profile WHERE user_id = ?", Integer.class, userId);
        if (n == null || n == 0) {
            throw new NotFoundException("User " + userId + " not found");
        }
    }

    public List<Map<String, Object>> transactions(String userId, String month, String runId, int page, int size) {
        requireUser(userId);
        String run = resolveRun(runId);
        return jdbc.queryForList("""
                SELECT t.transaction_id, t.account_id, t.posted_at_utc, c.local_date, c.budget_month, t.amount,
                       t.descriptor, t.txn_type, t.merchant_id, c.category_code, c.method, c.rule_id, c.confidence,
                       c.is_transfer, c.transfer_group_id, c.is_refund, c.refund_of_transaction_id,
                       c.recurring_series_id, c.spend_impact
                FROM transaction_classification c
                JOIN ledger_transaction t ON t.transaction_id = c.transaction_id
                WHERE c.run_id = ? AND c.user_id = ? AND (?::varchar IS NULL OR c.budget_month = ?)
                ORDER BY t.posted_at_utc, t.transaction_id
                LIMIT ? OFFSET ?""", run, userId, month, month, size, (long) page * size);
    }

    public Map<String, Object> budgetReport(String userId, String month, String runId) {
        requireUser(userId);
        String run = resolveRun(runId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.budget_id, b.supplied_code, b.resolved_code, b.resolution, b.budget_amount, b.actual_amount,
                       b.variance, b.utilization, b.status
                FROM budget_comparison b
                WHERE b.run_id = ? AND b.user_id = ? AND b.budget_month = ?
                ORDER BY b.utilization DESC""", run, userId, month);
        List<Map<String, Object>> unbudgeted = jdbc.queryForList("""
                SELECT c.category_code, sum(c.spend_impact) AS actual_amount, count(*) AS txn_count
                FROM transaction_classification c
                JOIN category k ON k.category_code = c.category_code AND k.kind = 'EXPENSE'
                WHERE c.run_id = ? AND c.user_id = ? AND c.budget_month = ? AND NOT c.is_transfer
                  AND c.category_code NOT IN (SELECT resolved_code FROM budget_comparison
                                              WHERE run_id = ? AND user_id = ? AND budget_month = ?
                                                AND resolved_code IS NOT NULL)
                GROUP BY c.category_code ORDER BY 2 DESC""", run, userId, month, run, userId, month);
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT coalesce(sum(spend_impact), 0) AS total_spend,
                       coalesce(sum(CASE WHEN is_transfer THEN abs(t.amount) ELSE 0 END) / 2, 0) AS transfers_excluded,
                       coalesce(sum(CASE WHEN is_refund THEN t.amount ELSE 0 END), 0) AS refunds_netted
                FROM transaction_classification c JOIN ledger_transaction t ON t.transaction_id = c.transaction_id
                WHERE c.run_id = ? AND c.user_id = ? AND c.budget_month = ?""", run, userId, month);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", run);
        out.put("user_id", userId);
        out.put("month", month);
        out.put("totals", totals);
        out.put("budgets", rows);
        out.put("unbudgeted_spend", unbudgeted);
        out.put("over_budget_count", rows.stream().filter(r -> "OVER".equals(r.get("status"))).count());
        return out;
    }

    public List<Map<String, Object>> recurring(String userId, String runId) {
        requireUser(userId);
        String run = resolveRun(runId);
        return jdbc.queryForList("""
                SELECT s.series_id, s.account_id, s.merchant_id, m.canonical_name AS merchant_name, s.kind, s.cadence,
                       s.typical_amount, s.first_date, s.last_date, s.occurrences, s.status, s.missed_dates,
                       s.price_change_date
                FROM recurring_series s JOIN merchant m ON m.merchant_id = s.merchant_id
                WHERE s.run_id = ? AND s.user_id = ? ORDER BY s.kind, s.series_id""", run, userId);
    }

    public List<Map<String, Object>> insights(String userId, String month, String runId) {
        requireUser(userId);
        String run = resolveRun(runId);
        return jdbc.queryForList("""
                SELECT insight_id, month, insight_type, category_code, actual_amount, reference_amount, severity, evidence
                FROM insight WHERE run_id = ? AND user_id = ? AND (?::varchar IS NULL OR month = ?)
                ORDER BY month, insight_id""", run, userId, month, month);
    }

    /** Full decision trail for one transaction: raw record, rule applied, links and series membership. */
    public Map<String, Object> trace(String transactionId, String runId) {
        String run = resolveRun(runId);
        List<Map<String, Object>> raw = jdbc.queryForList("""
                SELECT t.*, a.user_id, u.timezone FROM ledger_transaction t
                JOIN account a ON a.account_id = t.account_id JOIN user_profile u ON u.user_id = a.user_id
                WHERE t.transaction_id = ?""", transactionId);
        if (raw.isEmpty()) {
            throw new NotFoundException("Transaction " + transactionId + " not found");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", run);
        out.put("transaction", raw.get(0));
        out.put("classification", first(jdbc.queryForList(
                "SELECT * FROM transaction_classification WHERE run_id = ? AND transaction_id = ?", run, transactionId)));
        out.put("rule", first(jdbc.queryForList("""
                SELECT r.* FROM category_rule r JOIN transaction_classification c ON c.rule_id = r.rule_id
                WHERE c.run_id = ? AND c.transaction_id = ?""", run, transactionId)));
        out.put("transfer_link", first(jdbc.queryForList(
                "SELECT * FROM transfer_link WHERE run_id = ? AND (out_transaction_id = ? OR in_transaction_id = ?)",
                run, transactionId, transactionId)));
        out.put("refund_link", first(jdbc.queryForList(
                "SELECT * FROM refund_link WHERE run_id = ? AND (refund_transaction_id = ? OR original_transaction_id = ?)",
                run, transactionId, transactionId)));
        out.put("recurring_series", first(jdbc.queryForList("""
                SELECT s.* FROM recurring_series s JOIN recurring_series_member m
                  ON m.run_id = s.run_id AND m.series_id = s.series_id
                WHERE m.run_id = ? AND m.transaction_id = ?""", run, transactionId)));
        out.put("exceptions", jdbc.queryForList(
                "SELECT exception_id, exception_type, status, detail FROM exception_record WHERE run_id = ? AND entity_id = ?",
                run, transactionId));
        return out;
    }

    public Map<String, Object> runSummary(String runId) {
        String run = resolveRun(runId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", run);
        out.put("category_mix", jdbc.queryForList("""
                SELECT category_code, method, count(*) AS txns, sum(spend_impact) AS spend
                FROM transaction_classification WHERE run_id = ? GROUP BY category_code, method ORDER BY 1, 2""", run));
        out.put("budget_status", jdbc.queryForList(
                "SELECT status, count(*) AS budgets FROM budget_comparison WHERE run_id = ? GROUP BY status ORDER BY 1", run));
        out.put("exceptions", jdbc.queryForList("""
                SELECT exception_type, status, count(*) AS n FROM exception_record WHERE run_id = ?
                GROUP BY exception_type, status ORDER BY 1, 2""", run));
        out.put("ledger_reconciliation", jdbc.queryForList("""
                SELECT count(*) AS accounts,
                       sum(CASE WHEN a.opening_balance + coalesce(t.total, 0) = a.closing_balance THEN 1 ELSE 0 END) AS reconciled
                FROM account a LEFT JOIN (SELECT account_id, sum(amount) AS total FROM ledger_transaction GROUP BY account_id) t
                  ON t.account_id = a.account_id""").get(0));
        return out;
    }

    private static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }
}
