# Budget Intelligence — Transaction Categorization, Recurring Payments & Household Budgets

YottaFlex.ai — Round 2, 72-hour code challenge (Associate AI Forward Deployed Engineer)
Author: **Pratanu Dolui** · Stack: **Java 17 (runs on 21), Spring Boot 3.3, Hibernate/JPA, PostgreSQL, Flyway, Maven**

This project categorizes household bank transactions, maps recurring payments (salary, rent, utilities,
subscriptions), compares spending against monthly budgets, and shows every decision and exception with a trace back to
the rule that produced it. It includes a deterministic synthetic data generator (1,000 households / 500,000
transactions / 12 months), separate ground-truth labels for the five required edge cases, a validation suite,
an evaluation harness with metrics, and a REST API backed by PostgreSQL.

---

## 1. Results at a glance (full dataset, seed 20261022)

| Area | Metric | Result |
|---|---|---|
| Data volume | users / accounts / transactions / budgets | 1,000 / 2,346 / **500,000** / 111,124 |
| Data validation | 62 checks: PK, FK, enums, ID formats, dates, lifecycle, reconciliation, duplicates | **62 / 62 PASS** |
| Reproducibility | 2 runs with the same seed, SHA-256 of every file | **identical** (`docs/reproducibility_full.json`) |
| Categorization | accuracy / macro-F1 / coverage | **0.904 / 0.948 / 99.5 %** |
| Transfer detection | precision / recall (30,066 legs) | **1.00 / 1.00** |
| Refund detection | precision / recall (6,678 refunds) | **1.00 / 1.00** (92 % linked to the exact original purchase) |
| Recurring series | precision / recall (9,065 series) | **0.997 / 0.997**, cadence accuracy 0.9999 |
| Missed recurring payments | precision / recall | 0.85 / 0.98 |
| Budget vs actual | mean abs. error per user-month-category | **$37.67** (naive baseline: $127.75) |
| Total spend | engine vs truth | −0.4 % (naive baseline overstates by **+28.9 %**) |
| Over-budget insights | precision / recall | 0.89 / 0.90 |
| Runtime | generate 500k / engine + evaluation / PostgreSQL load + run | 2.4 s / 3.7 s / 4 s + 13 s |

**Edge-case handling (labelled in ground truth, scored by `evaluate`):**

| Required edge case | Labelled rows | Handled correctly | How |
|---|---:|---:|---|
| Transfer counted as spending twice | 16,164 legs | **100 %** | Pair own-account legs (same user, equal amount, ≤4 days); paired legs → `XFER`/`CCPY`, spend impact 0 |
| Refund treated as new expense | 1,737 | **92 %** (100 % detected; 8 % linked to a different purchase of the same merchant/amount) | Positive amount at an expense merchant = refund regardless of bank `txn_type`; inherits original purchase's category; nets spend |
| Recurring series skips incorrectly | 98 false skips + 300 true skips | **100 %** of false skips not flagged; **98 %** of true skips flagged | Expected due dates matched to postings within ±7 days (one-to-one), instead of month buckets |
| Transaction date crosses timezone boundary | 5,739 | **100 %** | Budget month = month of `posted_at_utc` converted to the account holder's timezone |
| Budget categories do not match taxonomy | 2,448 budget rows | **100 %** | Exact → normalized (trim/upper) → alias table → else `UNMAPPED` + HIGH-severity exception |

Full report: `data/full/evaluation_report.json` (also stored per run in PostgreSQL and served by
`GET /api/pipeline/runs/{runId}/evaluation`).

---

## 2. Quick start

Detailed Windows 11 instructions (installing JDK, Maven, PostgreSQL, Git; every command) are in
**`docs/Windows11_Setup_and_Run_Guide.docx`**. Short version:

```bash
# 1. Build + run all tests (unit + integration against a real embedded PostgreSQL; no Docker needed)
mvn clean verify

# 2. Generate data (already committed: data/starter; data/full is in data/full_dataset.zip)
java -jar target/budget-intelligence.jar generate --scale starter        # 100 users / 50k txns
java -jar target/budget-intelligence.jar generate --scale full           # 1,000 users / 500k txns
#   each generate also runs the 62 validation checks and writes data/<scale>/validation_summary.json

# 3. Score the engine offline against ground truth (no database needed)
java -jar target/budget-intelligence.jar evaluate --data data/full

# 4. Reproducibility: generate twice, compare row counts + SHA-256
java -jar target/budget-intelligence.jar repro --scale full

# 5. Run the API on PostgreSQL
psql -U postgres -h localhost -f scripts/sql/create_database.sql     # role budget_app / db budget_intel
java -jar target/budget-intelligence.jar                              # Flyway creates the schema on start
curl -X POST localhost:8080/api/admin/datasets/load -H "Content-Type: application/json" -d "{\"path\":\"data/starter\"}"
curl -X POST localhost:8080/api/pipeline/runs
curl "localhost:8080/api/users/USR-000001/budget-report?month=2026-03"
```

A scripted walkthrough of every role's endpoints: `scripts/windows/demo-api.ps1` (PowerShell) or
`scripts/unix/demo-api.sh` (curl).

Database connection defaults (`application.yml`) are local-only development values and can be overridden with
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. No secrets are committed.

---

## 3. Architecture

```
                      +-------------------------------------------------------------+
  generate (CLI) ---> | SyntheticDataGenerator  (fixed seed, scale = tiny/starter/full) |
                      +-----------------------------+-------------------------------+
                                                    | writes
                 data/<scale>/input/*.csv  (engine-visible)    data/<scale>/ground_truth/*.csv  (labels only)
                 manifest.json, validation_summary.json         evaluation_report.json
                          |                                               |
         validate (CLI) --+--> DatasetValidator (62 checks)               |
                          |                                               |
   evaluate (CLI) ---- CSV ---> BudgetIntelligenceEngine  ----> Evaluator <+  (also scores a naive baseline)
                          |               ^
  POST /api/admin/        |               | Dataset (records)
  datasets/load  -- COPY -+--> PostgreSQL +---- DatasetReader
                                   ^
  POST /api/pipeline/runs ---------+---- PipelineService: run engine, append outputs under RUN-xxxxxx (COPY)
  GET  /api/users/..., /api/transactions/{id}/trace, /api/exceptions ... (JPA + JdbcTemplate read models)
```

**Package layout** (`src/main/java/com/yottaflex/budget`)

| Package | Responsibility |
|---|---|
| `generator` | `Catalog` (taxonomy, fictional merchants + descriptor aliases, legacy category aliases, 137 rules), `SyntheticDataGenerator`, `GeneratorConfig` |
| `model` | Immutable records for every entity (amounts in signed `long` cents) plus `Dataset` / `GroundTruth` containers |
| `engine` | `RuleCategorizer`, `RecurringDetector`, `BudgetIntelligenceEngine` (orchestrates transfers, refunds, timezone, budgets, insights, exceptions), `InsightRules` |
| `evaluation` | `Evaluator` — categorization P/R/F1, transfer/refund/recurring metrics, timezone, budget taxonomy, actuals MAE vs naive baseline, insights, per-edge-case rates |
| `validation` | `DatasetValidator` — the Step 8 checks |
| `cli` | `generate`, `validate`, `evaluate`, `repro` commands (run without Spring or a database) |
| `persistence` | JPA entities + Spring Data repositories |
| `service` | Dataset loading (PostgreSQL `COPY`), pipeline runs, read models, exception review lifecycle, budget changes |
| `web` | REST controllers per actor, DTOs, `GlobalExceptionHandler` (structured JSON errors) |

**Why the engine is plain Java:** the same decision code runs from CSV (fast offline evaluation, reproducible
metrics for reviewers without a database) and from PostgreSQL (the product path). Spring/JPA sits around it, not
inside it, which keeps the rules unit-testable.

---

## 4. Workflow and business rules

The order follows the brief:

1. **Master data first** — taxonomy (31 categories in 2 levels), 94 fictional merchants (with descriptor aliases),
   merchant↔category bridge, 10 legacy category aliases, 137 categorization rules, 1,000 user profiles, 2,346 accounts.
2. **Dependent records with valid parent IDs** — recurring series (salary, rent/mortgage, utilities, insurance,
   subscriptions), then discretionary purchases + refunds, then derived rows (savings transfers, monthly interest from
   the running savings balance, card payments equal to the previous month's card activity).
3. **Business logic** — signed amounts (negative = money out), merchant descriptors with aliases
   (`NWM*NORTHWIND 482`, `SQ *BLUEBIRD CAFE`), seasonal spending (December shopping/gifts, summer travel, August
   education, winter/summer electricity), monthly budgets per household, insights from aggregations.
4. **State changes as records** — pipeline outputs are appended per `run_id` (never updated in place);
   exception status changes go to `exception_event`; budget amount changes go to `budget_event`; dataset loads go to
   `dataset_load`; failed runs are kept with their error message.
5. **Ground truth from documented rules**, not random assignment — e.g. true category comes from the purchase type
   the generator chose; `TIMEZONE_BOUNDARY` is labelled whenever local month ≠ UTC month; monthly actuals and expected
   insights are computed from the labelled ledger with the same `InsightRules` thresholds the engine uses.

### Engine decision order (per pipeline run)

| Step | Rule | Output / traceability |
|---|---|---|
| 1. Local date | `posted_at_utc` → user's IANA timezone → `local_date`, `budget_month` | `transaction_classification.local_date` |
| 2. Rule categorization | Active, effective-dated rules by priority: `TXN_TYPE`(10) → `DESCRIPTOR_REGEX`(20) → `MERCHANT`(30) → `MERCHANT_AMOUNT`(40) → `MCC`(50) → fallback `UNCT` | `rule_id`, `method`, `confidence` |
| 3. Transfers | Outflow (`TRANSFER`/`ACH_DEBIT`/`BILL_PAY` or transfer-like rule) paired with an inflow on **another account of the same user**, equal amount, 0–4 days later, not salary/interest | `transfer_link`, category `XFER`/`CCPY`, spend impact 0 |
| 4. Refunds | Positive amount whose rule category is an expense (or `CARD_REFUND`) → refund; linked to the latest unconsumed purchase at the same account+merchant within 120 days (exact amount preferred) | `refund_link`; refund takes the original's category and reduces spend |
| 5. Recurring | Group by account+merchant; median gap 26–34 d → MONTHLY, 13–15 → BIWEEKLY, 6–8 → WEEKLY; ≥75 % regular gaps; amount CV ≤ 0.30 (or exact-amount cluster for mixed merchants); due dates matched ±7 d (±4 d biweekly) | `recurring_series`, `recurring_series_member` (bridge), missed dates, price change |
| 6. Actuals | Σ −amount for expense categories, transfers excluded, refunds netted, by local month | `spend_impact` |
| 7. Budgets | Code resolution EXACT → NORMALIZED → ALIAS → UNMAPPED; group codes sum their children | `budget_comparison` (UNDER / NEAR / OVER / UNMAPPED) |
| 8. Insights | `OVER_BUDGET` (> 100 %; HIGH if > 125 %), `NEAR_BUDGET` (90–100 %), `MISSED_RECURRING`, `RECURRING_PRICE_CHANGE`, `SPENDING_SPIKE` (> 1.75× user's monthly average and > $200) | `insight` with evidence (`budget_id=…`, `series_id=…`) |
| 9. Exceptions | `UNCATEGORIZED_TRANSACTION`, `UNMATCHED_TRANSFER_LEG`, `REFUND_WITHOUT_ORIGINAL`, `MISSED_RECURRING_PAYMENT`, `BUDGET_CATEGORY_UNMAPPED` | `exception_record` (OPEN), reviewed via API |

### Actors and endpoints

| Actor | Endpoints |
|---|---|
| System administrator | `POST /api/admin/datasets/load` `{"path": "data/starter"}` · `GET /api/admin/stats` |
| Operations analyst | `POST /api/pipeline/runs` · `GET /api/pipeline/runs` · `GET /api/pipeline/runs/{runId}` (summary, ledger reconciliation) · `GET /api/pipeline/runs/{runId}/evaluation` |
| Customer / account holder | `GET /api/users/{userId}` · `/transactions?month=&page=&size=` · `/budget-report?month=` · `/recurring` · `/insights?month=` · `PUT /api/budgets/{budgetId}/amount` · `GET /api/budgets/{budgetId}/history` |
| Risk / compliance reviewer | `GET /api/exceptions?status=&type=&userId=` · `POST /api/exceptions/{id}/transitions` `{"toStatus": "IN_REVIEW", "note": "…"}` · `GET /api/exceptions/{id}/events` |
| Anyone (traceability) | `GET /api/transactions/{txnId}/trace` — raw row, classification, rule, transfer/refund link, series, exceptions |

All read endpoints accept an optional `runId` (default: latest completed run). The acting user is taken from the
`X-Actor` header and recorded on runs, events and loads. Exception lifecycle:
`OPEN → IN_REVIEW → RESOLVED | DISMISSED`, `OPEN → DISMISSED`, `IN_REVIEW → OPEN`; anything else returns
**409 Conflict**. Errors are JSON: `{timestamp, status, error, message, path}` (400 validation, 404 not found,
409 business-rule conflict, 500 unexpected).

---

## 5. Data

| Folder | Contents |
|---|---|
| `data/starter/` | 100 users / 50,000 transactions — committed as plain CSV for quick review |
| `data/full_dataset.zip` | 1,000 users / 500,000 transactions (17 MB; raw CSVs ~110 MB are git-ignored; extract it at the project root to recreate `data/full/`, or regenerate — byte-identical, see checksums in `data/full/manifest.json`) |
| `data/<scale>/input/` | `user_profiles, accounts, merchants, merchant_categories, categories, category_aliases, category_rules, transactions, budgets` (.csv) + `taxonomy.json` |
| `data/<scale>/ground_truth/` | `transaction_labels.csv` (true category, transfer group, refund-of, series, local date, budget month, `issue_type`, `expected_decision`), `recurring_series.csv` (REC-000001…, missed/shifted dates, price change), `budget_labels.csv`, `monthly_actuals.csv`, `insights.csv` |
| `data/<scale>/manifest.json` | generator, command, seed, scale, provenance, row counts and SHA-256 per file |
| `data/<scale>/validation_summary.json` · `evaluation_report.json` | outputs of `validate` / `evaluate` |

Ground truth is never read by the engine or loaded into PostgreSQL; only `Evaluator` reads it.
Data dictionary (every field: type, required, allowed values, units, uniqueness, validation): **`docs/DATA_DICTIONARY.md`**.
SQL schema: `src/main/resources/db/migration/V1__schema.sql`. Seed alternatives: the API loader (PostgreSQL `COPY`,
tested) or `scripts/sql/load_dataset.sql` (psql `\copy`).

**ID conventions** (zero-padded, immutable, consistent across files): `USR-000001`, `ACC-USR-000001-01`,
`TXN-2026-00000001` (assigned in posting order), `BUD-USR-000001-2026-01-GROC`, `REC-000001`, plus `MER-0001`,
`RUL-0001`, `TRG-0000001` (transfer group, ground truth), `TRF-0000001`, `INS-0000001`, `EXC-0000001`, `RUN-000001`.

**Provenance:** 100 % synthetic. No external dataset was used; merchant, employer, landlord and person names are
invented. MCC codes are used only as generic category hints.

---

## 6. Testing and verification

| What | How |
|---|---|
| Unit tests (19) | Generator volume/IDs/edge-case presence/same-seed checksums; validator catches broken FK and ledger break; recurring detector (early posting is not a skip, genuine skip flagged, cancelled ≠ missed, irregular ≠ recurring); engine edge-case rates and engine-beats-naive |
| Integration tests (8) | `ApiIntegrationTest` boots Spring against a **real PostgreSQL** (zonky embedded binaries): Flyway migration, COPY load, pipeline run, every actor's endpoints, lifecycle 409s, budget history, 400/404 error bodies, failed run kept in history |
| Full-scale DB test (opt-in) | `mvn test -Dtest=FullScaleDatabaseTest -DfullScale=<abs path to data/full>` — loads 500k rows and runs the pipeline (≈ 4 s + 13 s) |
| Data validation | `validate` — 62 checks; also run automatically after `generate` |
| Reproducibility | `repro --scale full` — output in `docs/reproducibility_check_output.txt` |
| SQL spot checks | `scripts/sql/validation_queries.sql` (ledger reconciliation, orphans, transfer pairs net to zero, …) |

`mvn clean verify` runs the 27 automated tests (the full-scale test is skipped unless `-DfullScale` is given).

---

## 7. Assumptions and key decisions

* **Calendar year 2026**, single currency (USD). Amounts are signed: negative = money leaves the account. Credit-card
  balances are negative when money is owed.
* **Budget month = the account holder's local month.** Banks post in UTC; households budget in local time.
* **Refunds net against their original category in the month the refund posts** (not retroactively in the purchase
  month), so closed months do not change. Ground truth uses the same convention.
* **Transfers and card payments are excluded from spending**; the card purchases themselves are the spend. This is the
  core fix for "counted twice".
* **Peer payments (QuickPay to another person), cash withdrawals and bank fees are spending**; only transfers between
  the user's own accounts are excluded.
* **Budgets** are generated from each household's average monthly spend × a per-household discipline factor
  (0.85–1.20; fixed costs × 1.05), so over/under-budget outcomes come from seasonality and behaviour, not random labels.
* **Ambiguous merchants are deliberate**: MegaMart (groceries/shopping/health), ShopSphere (shopping/education/
  groceries + a membership subscription), Cornerstone Pharmacy. Their true category is not recoverable from the
  descriptor, which is why categorization accuracy is ~90 % rather than ~100 % — and is the honest place to improve.
* **Dataset reload** replaces master data and the run outputs that reference it; `pipeline_run` and `dataset_load`
  rows are kept as an audit trail.
* No authentication: actors are identified by the `X-Actor` header (see limitations).

## 8. Limitations and next steps

* **Ambiguous-merchant categorization** is the main error source (GROC↔SHOP↔HLTH at MegaMart; HLTH F1 0.58). The
  `MERCHANT_AMOUNT` band rule is intentionally simple. Next: learn per-merchant amount/basket priors from reviewer
  feedback, or use item-level data when available.
* **Refund linking** chooses the most recent same-amount purchase; 8 % of refunds link to a different purchase with the
  same merchant and amount (category impact is identical, the link is not).
* **Missed-payment false positives** (precision 0.85): mostly utility/subscription series near the data edges and
  biweekly drift. Next: per-kind tolerance windows and a confirmation delay before raising an exception.
* **No authentication/authorization**; role separation is by endpoint only. Next: Spring Security with roles mapped to
  the four actors.
* **Pipeline runs synchronously** (≈ 13 s at 500k rows). Next: async job + status polling for larger volumes.
* Exception review is per pipeline run; carrying review decisions across runs (e.g. "merchant fixed") is not
  implemented.
* `scripts/sql/load_dataset.sql` (psql `\copy`) was not executed in my environment (no psql client available); the
  tested load path is the API loader, which uses the same COPY semantics.

## 9. Use of AI (transparency)

Built with **Claude Code (Anthropic, model Claude Opus)** as a coding assistant. Full prompt history:
**`prompts/PROMPT_HISTORY.md`**.

* **Where AI accelerated the work:** reading the brief and turning it into a design; boilerplate (records, JPA
  entities, controllers, CSV I/O, SQL DDL); first drafts of the generator, engine, evaluator and documentation.
* **Where I relied on it heavily:** the synthetic-data model (distributions, seasonality, edge-case injection) and the
  evaluation harness structure.
* **What was reviewed, changed and validated:** every metric in this README comes from running the code
  (`evaluate`, `repro`, the test suite, the full-scale PostgreSQL test). Issues found by running and fixed during
  development include: the validator crashing (NPE) on orphan foreign keys instead of reporting them; Bean Validation
  errors returning HTTP 500 instead of 400; dataset reload erasing pipeline-run history; timezone-boundary injection
  running after card-payment derivation (statement amounts would not match); Jackson pretty-printing with the OS line
  separator, which would make checksums differ on Windows; and an untestable psql variable-interpolation pattern in
  the SQL seed script, replaced with relative paths. I can walk through any design decision or line of code.
