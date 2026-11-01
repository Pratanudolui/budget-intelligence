-- Manual seed (alternative to POST /api/admin/datasets/load). The schema must already exist
-- (start the application once so Flyway creates it). Usage from the project root:
--   cd data/starter/input
--   psql -U budget_app -h localhost -d budget_intel -f ../../../scripts/sql/load_dataset.sql
-- (\copy reads files relative to the directory psql was started from.)
-- Parent tables are loaded before children so every foreign key resolves.
\set ON_ERROR_STOP on
BEGIN;
TRUNCATE exception_event, exception_record, insight, budget_comparison, recurring_series_member, recurring_series,
         refund_link, transfer_link, transaction_classification, budget_event, budget, ledger_transaction, account,
         user_profile, category_rule, merchant_category, merchant, category_alias, category RESTART IDENTITY;
\copy category FROM 'categories.csv' WITH (FORMAT csv, HEADER true)
\copy category_alias FROM 'category_aliases.csv' WITH (FORMAT csv, HEADER true)
\copy merchant FROM 'merchants.csv' WITH (FORMAT csv, HEADER true)
\copy merchant_category FROM 'merchant_categories.csv' WITH (FORMAT csv, HEADER true)
\copy category_rule FROM 'category_rules.csv' WITH (FORMAT csv, HEADER true)
\copy user_profile FROM 'user_profiles.csv' WITH (FORMAT csv, HEADER true)
\copy account FROM 'accounts.csv' WITH (FORMAT csv, HEADER true)
\copy ledger_transaction FROM 'transactions.csv' WITH (FORMAT csv, HEADER true)
\copy budget FROM 'budgets.csv' WITH (FORMAT csv, HEADER true)
COMMIT;
SELECT 'user_profile' AS tbl, count(*) FROM user_profile
UNION ALL SELECT 'account', count(*) FROM account
UNION ALL SELECT 'ledger_transaction', count(*) FROM ledger_transaction
UNION ALL SELECT 'budget', count(*) FROM budget;
