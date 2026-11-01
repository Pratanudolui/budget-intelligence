-- Ad-hoc SQL checks a reviewer can run after loading data and running the pipeline.
-- 1. Ledger reconciliation: opening balance + sum(transactions) = closing balance for every account
SELECT count(*) AS accounts,
       count(*) FILTER (WHERE a.opening_balance + coalesce(t.total, 0) = a.closing_balance) AS reconciled
FROM account a
LEFT JOIN (SELECT account_id, sum(amount) AS total FROM ledger_transaction GROUP BY account_id) t USING (account_id);

-- 2. Orphan checks (should all be 0)
SELECT 'txn->account' AS fk, count(*) FROM ledger_transaction t LEFT JOIN account a USING (account_id) WHERE a.account_id IS NULL
UNION ALL
SELECT 'budget->user', count(*) FROM budget b LEFT JOIN user_profile u USING (user_id) WHERE u.user_id IS NULL;

-- 3. Transfer pairs net to zero (latest run)
WITH r AS (SELECT run_id FROM pipeline_run WHERE status = 'COMPLETED' ORDER BY started_at DESC LIMIT 1)
SELECT count(*) AS pairs, count(*) FILTER (WHERE o.amount + i.amount = 0) AS net_zero
FROM transfer_link l JOIN r USING (run_id)
JOIN ledger_transaction o ON o.transaction_id = l.out_transaction_id
JOIN ledger_transaction i ON i.transaction_id = l.in_transaction_id;

-- 4. Spend by category for one household and month (latest run)
WITH r AS (SELECT run_id FROM pipeline_run WHERE status = 'COMPLETED' ORDER BY started_at DESC LIMIT 1)
SELECT c.category_code, sum(c.spend_impact) AS spend, count(*) AS txns
FROM transaction_classification c JOIN r USING (run_id)
WHERE c.user_id = 'USR-000001' AND c.budget_month = '2026-03' AND c.spend_impact <> 0
GROUP BY 1 ORDER BY 2 DESC;

-- 5. Exception queue by type and status (latest run)
WITH r AS (SELECT run_id FROM pipeline_run WHERE status = 'COMPLETED' ORDER BY started_at DESC LIMIT 1)
SELECT exception_type, status, count(*) FROM exception_record JOIN r USING (run_id) GROUP BY 1, 2 ORDER BY 1, 2;
