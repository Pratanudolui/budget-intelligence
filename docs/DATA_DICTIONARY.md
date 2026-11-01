# Data Dictionary

Conventions for every table:

* **Money** is `NUMERIC(14,2)` in USD (in CSV: plain decimal, `.` separator). Transaction amounts are **signed**:
  negative = money leaves the account, positive = money arrives. Internally the Java code uses `long` cents.
* **Dates** are ISO-8601 `YYYY-MM-DD`; **timestamps** are ISO-8601 UTC instants (`2026-03-01T14:30:00Z`).
* **Months** are `YYYY-MM` and always mean the account holder's **local** month.
* "Req" = required (NOT NULL). "Unique" = enforced by a primary key or unique constraint.
* CSV column names are identical to the PostgreSQL column names (`src/main/resources/db/migration/V1__schema.sql`).
* Every rule below is checked by `DatasetValidator` (62 checks) and/or by database constraints.

## Relationships and cardinality

| Relationship | Cardinality | Optional? | Implemented by |
|---|---|---|---|
| UserProfile → Account | 1 : N (1–3) | every user has a CHECKING `-01`; SAVINGS (70 %) and CREDIT_CARD (65 %) optional | `account.user_id` FK |
| Account → Transaction | 1 : N | an account may have no rows | `ledger_transaction.account_id` FK |
| Merchant → Transaction | 1 : N | **optional** — unknown merchants have `merchant_id` NULL | `ledger_transaction.merchant_id` FK (nullable) |
| Merchant ↔ Category | **N : M** | multi-category merchants have no default | bridge `merchant_category` |
| Category → Category (parent) | N : 1 | top-level groups have no parent | `category.parent_code` FK |
| Category → CategoryRule | 1 : N | | `category_rule.category_code` FK |
| Merchant → CategoryRule | 1 : N | optional (only MERCHANT / MERCHANT_AMOUNT rules) | `category_rule.merchant_id` FK |
| CategoryAlias → Category | N : 1 | | `category_alias.category_code` FK |
| UserProfile → Budget | 1 : N (one per category per month) | | `budget.user_id` FK; `budget.category_code` intentionally **not** an FK (may not match taxonomy) |
| Budget → BudgetEvent | 1 : N | | `budget_event.budget_id` FK (history of amount changes) |
| RecurringSeries ↔ Transaction | **N : M** per run | | bridge `recurring_series_member` |
| PipelineRun → outputs | 1 : N | | `run_id` FK on every output table |
| Transaction → TransactionClassification | 1 : N (one per run) | | PK `(run_id, transaction_id)` |
| Transaction ↔ Transaction (transfer pair) | 1 : 1 | | `transfer_link (out_transaction_id, in_transaction_id)` |
| Transaction → Transaction (refund of) | N : 1 | | `refund_link.original_transaction_id` |
| ExceptionRecord → ExceptionEvent | 1 : N | | `exception_event.exception_pk` FK |

## Lifecycle states and effective dates

| Entity | States / dates | Rules |
|---|---|---|
| UserProfile | `ACTIVE`; `created_on` | 8 % of users join during 2026 → no activity before `created_on` |
| Account | `OPEN` / `CLOSED`; `opened_on`, `closed_on` | `CLOSED` ⇔ `closed_on` set; no transactions before `opened_on` or after `closed_on`; `opened_on ≥ user.created_on` |
| Category | `ACTIVE` / `DEPRECATED`; `effective_from` | |
| CategoryRule | `ACTIVE` / `INACTIVE`; `effective_from`, `effective_to` | engine applies a rule only when the transaction's local date is within its effective range; `effective_to ≥ effective_from` |
| Budget | `ACTIVE`; `effective_from` = first day of `budget_month` | amount changes are appended to `budget_event` |
| RecurringSeries | `ACTIVE` / `ENDED` | `ENDED` when no posting is seen within one cadence + tolerance before the end of data |
| PipelineRun | `RUNNING → COMPLETED` or `RUNNING → FAILED` | failed runs are kept with `error_message` |
| ExceptionRecord | `OPEN → IN_REVIEW → RESOLVED / DISMISSED`; `OPEN → DISMISSED`; `IN_REVIEW → OPEN` | other transitions return HTTP 409; each change writes an `exception_event` row |

---

## UserProfile — `input/user_profiles.csv` → `user_profile`

| Field | Type | Req | Allowed values / units | Unique | Validation |
|---|---|---|---|---|---|
| user_id | VARCHAR(16) | Y | `USR-` + 6 digits | **PK** | regex `^USR-[0-9]{6}$`, sequential from USR-000001 |
| display_name | VARCHAR(80) | Y | synthetic first + last name | | non-empty |
| household_size | INTEGER | Y | 1–5 (DB allows 1–10) persons | | CHECK range |
| timezone | VARCHAR(40) | Y | IANA zone: America/New_York, America/Chicago, America/Denver, America/Los_Angeles, Asia/Kolkata, Europe/London | | must parse as `ZoneId` |
| income_band | VARCHAR(8) | Y | LOW, MID, HIGH | | enum |
| annual_income | NUMERIC(14,2) | Y | USD / year, gross; LOW 30–55k, MID 55–120k, HIGH 120–300k | | > 0 |
| city | VARCHAR(40) | Y | city consistent with timezone | | |
| created_on | DATE | Y | 2018-01-01 … 2026-07-28 | | ≤ first transaction date |
| status | VARCHAR(16) | Y | ACTIVE | | enum |

## Account — `input/accounts.csv` → `account`

| Field | Type | Req | Allowed values / units | Unique | Validation |
|---|---|---|---|---|---|
| account_id | VARCHAR(24) | Y | `ACC-<user_id>-NN` (NN = 01, 02, 03) | **PK** | regex; embedded user id must equal `user_id` |
| user_id | VARCHAR(16) | Y | | | FK → user_profile |
| account_type | VARCHAR(16) | Y | CHECKING (always `-01`), SAVINGS, CREDIT_CARD | | enum |
| opened_on | DATE | Y | | | ≥ user.created_on |
| closed_on | DATE | N | | | required iff status = CLOSED |
| status | VARCHAR(16) | Y | OPEN, CLOSED | | enum |
| currency | VARCHAR(3) | Y | USD | | enum |
| opening_balance | NUMERIC(14,2) | Y | USD at 2026-01-01 (or opening date); credit card starts at 0 | | |
| closing_balance | NUMERIC(14,2) | Y | USD, **derived** | | = opening_balance + Σ transaction.amount (reconciled) |

## Merchant — `input/merchants.csv` → `merchant`

| Field | Type | Req | Allowed values / units | Unique | Validation |
|---|---|---|---|---|---|
| merchant_id | VARCHAR(16) | Y | `MER-` + 4 digits | **PK** | regex |
| canonical_name | VARCHAR(120) | Y | fictional name | | |
| merchant_type | VARCHAR(20) | Y | RETAIL, SUBSCRIPTION, UTILITY, INSURER, LENDER, LANDLORD, EMPLOYER, P2P, FINANCIAL | | enum |
| default_category_code | VARCHAR(16) | N | taxonomy code; NULL for multi-category merchants (MegaMart, ShopSphere, Cornerstone Pharmacy, First Synthetic Bank) | | FK → category |
| mcc | VARCHAR(4) | N | ISO 18245 merchant category code | | 4 digits |
| status | VARCHAR(16) | Y | ACTIVE | | enum |

## MerchantCategory (bridge, N:M) — `input/merchant_categories.csv` → `merchant_category`

| Field | Type | Req | Allowed values | Unique | Validation |
|---|---|---|---|---|---|
| merchant_id | VARCHAR(16) | Y | | PK part | FK → merchant |
| category_code | VARCHAR(16) | Y | | PK part | FK → category |
| is_primary | BOOLEAN | Y | true when equal to the merchant's default category | | |

## Category — `input/categories.csv` → `category`

| Field | Type | Req | Allowed values / units | Unique | Validation |
|---|---|---|---|---|---|
| category_code | VARCHAR(16) | Y | 4-letter code (see taxonomy below) | **PK** | |
| name | VARCHAR(80) | Y | | | |
| parent_code | VARCHAR(16) | N | group code; NULL for groups | | FK → category |
| kind | VARCHAR(16) | Y | GROUP, INCOME, EXPENSE, TRANSFER, UNKNOWN | | enum; only EXPENSE counts toward spending |
| essential | BOOLEAN | Y | | | |
| effective_from | DATE | Y | 2025-01-01 | | |
| status | VARCHAR(16) | Y | ACTIVE, DEPRECATED | | enum |

Taxonomy: **INCM** Income (SALR Salary, INTR Interest) · **HOME** (RENT, MORT Mortgage, UTIL Utilities) · **FOOD**
(GROC Groceries, DINE Dining) · **MOVE** (FUEL, TRNS Transit & Rideshare) · **LIFE** (SHOP, ENTR, SUBS, HLTH, TRVL,
GIFT, EDUC) · **FINC** (INSU Insurance, FEES Bank Fees) · **XFRS** Transfers (XFER Internal Transfer, CCPY Credit Card
Payment) · **MISC** (PEER Peer Payments, CASH Cash Withdrawal, UNCT Uncategorized).

## CategoryAlias — `input/category_aliases.csv` → `category_alias`

| Field | Type | Req | Allowed values | Unique | Validation |
|---|---|---|---|---|---|
| alias_code | VARCHAR(40) | Y | upper-case legacy code (GROCERY, GROCERIES, DINING, RESTAURANTS, SUBSCRIPTIONS, ENTERTAINMENT, GAS, TRANSPORT, UTILITIES, HEALTH) | **PK** | |
| category_code | VARCHAR(16) | Y | | | FK → category |

## CategoryRule — `input/category_rules.csv` → `category_rule`

| Field | Type | Req | Allowed values / units | Unique | Validation |
|---|---|---|---|---|---|
| rule_id | VARCHAR(16) | Y | `RUL-` + 4 digits | **PK** | |
| priority | INTEGER | Y | 10 TXN_TYPE, 20 DESCRIPTOR_REGEX, 30 MERCHANT, 40 MERCHANT_AMOUNT, 50 MCC (lower runs first; ties by rule_id) | | |
| match_type | VARCHAR(20) | Y | TXN_TYPE, DESCRIPTOR_REGEX, MERCHANT, MERCHANT_AMOUNT, MCC | | enum |
| merchant_id | VARCHAR(16) | cond. | required for MERCHANT / MERCHANT_AMOUNT | | FK → merchant |
| pattern | VARCHAR(200) | cond. | Java regex, required for DESCRIPTOR_REGEX | | must compile |
| mcc | VARCHAR(4) | cond. | required for MCC | | |
| txn_type | VARCHAR(20) | cond. | required for TXN_TYPE | | |
| min_abs_amount / max_abs_amount | NUMERIC(14,2) | N | USD, inclusive bounds on \|amount\| | | min ≤ max |
| category_code | VARCHAR(16) | Y | | | FK → category |
| effective_from | DATE | Y | | | |
| effective_to | DATE | N | | | ≥ effective_from |
| status | VARCHAR(16) | Y | ACTIVE, INACTIVE | | |

## Transaction — `input/transactions.csv` → `ledger_transaction`

| Field | Type | Req | Allowed values / units | Unique | Validation |
|---|---|---|---|---|---|
| transaction_id | VARCHAR(24) | Y | `TXN-2026-` + 8 digits, assigned in posting order | **PK** | regex |
| account_id | VARCHAR(24) | Y | | | FK → account; posted within account open dates |
| merchant_id | VARCHAR(16) | N | NULL for merchants not in master data (~2 % of purchases) | | FK → merchant |
| posted_at_utc | TIMESTAMPTZ | Y | UTC instant | | local date ≥ account.opened_on |
| amount | NUMERIC(14,2) | Y | USD, signed (− out / + in) | | ≠ 0 |
| currency | VARCHAR(3) | Y | USD | | enum |
| descriptor | VARCHAR(120) | Y | raw bank text with aliases, store numbers, codes | | |
| txn_type | VARCHAR(20) | Y | CARD_PURCHASE, CARD_REFUND, ACH_DEBIT, ACH_CREDIT, TRANSFER, BILL_PAY, PAYMENT, INTEREST, FEE, ATM, P2P | | enum (bank channel, may be wrong for refunds — edge case) |
| mcc | VARCHAR(4) | N | | | |
| balance_after | NUMERIC(14,2) | Y | USD, **derived** running balance of the account after this row | | chain reconciles from opening_balance in TXN-ID order |

No label columns exist in this file; true categories live only in `ground_truth/transaction_labels.csv`.

## Budget — `input/budgets.csv` → `budget`

| Field | Type | Req | Allowed values / units | Unique | Validation |
|---|---|---|---|---|---|
| budget_id | VARCHAR(64) | Y | `BUD-<user_id>-<YYYY-MM>-<CODE>` where CODE = supplied code trimmed, upper-cased, spaces → `_` | **PK** | must match its own user / month / code fields |
| user_id | VARCHAR(16) | Y | | | FK → user_profile |
| budget_month | VARCHAR(7) | Y | `2026-01` … `2026-12` (local month) | (user, month, code) unique | regex |
| category_code | VARCHAR(40) | Y | **as supplied** — normally a taxonomy code; ~2 % are legacy/free text (`GROCERY`, `groceries `, `Groc`, `PETS`, …) | | resolved by the engine, not by FK |
| budget_amount | NUMERIC(14,2) | Y | USD per month | | > 0 |
| currency | VARCHAR(3) | Y | USD | | |
| effective_from | DATE | Y | first day of budget_month | | equals month start |
| status | VARCHAR(16) | Y | ACTIVE | | |

## RecurringSeries — engine output `recurring_series` (truth: `ground_truth/recurring_series.csv`)

| Field | Type | Req | Allowed values / units | Unique | Validation |
|---|---|---|---|---|---|
| run_id | VARCHAR(16) | Y (DB) | `RUN-` + 6 digits | PK part | FK → pipeline_run |
| series_id | VARCHAR(16) | Y | `REC-` + 6 digits (truth: in generation order; engine: sorted by user, account, merchant, first date) | PK (per run) | regex |
| user_id / account_id / merchant_id | VARCHAR | Y | | | FKs |
| kind | VARCHAR(20) | Y | SALARY, RENT, MORTGAGE, UTILITY, INSURANCE, SUBSCRIPTION, OTHER | | enum |
| cadence | VARCHAR(12) | Y | WEEKLY, BIWEEKLY, MONTHLY | | enum |
| typical_amount (truth: expected_amount) | NUMERIC(14,2) | Y | USD, median absolute amount | | > 0 |
| first_date / last_date (truth: start_date / end_date) | DATE | Y | | | first ≤ last |
| occurrences | INTEGER | Y | count of member transactions | | equals bridge / label count |
| status | VARCHAR(12) | Y | ACTIVE, ENDED | | enum |
| missed_dates | VARCHAR(400) | N | `;`-separated due dates with no matching posting | | |
| price_change_date | DATE | N | first posting at the new price (subscriptions) | | |
| shifted_dates (truth only) | | N | due dates whose payment posted early in the previous month | | |
| issue_type (truth only) | | Y | NONE, RECURRING_TRUE_SKIP, RECURRING_FALSE_SKIP | | |

## Insight — engine output `insight` (expected: `ground_truth/insights.csv`)

| Field | Type | Req | Allowed values / units | Unique | Validation |
|---|---|---|---|---|---|
| insight_id | VARCHAR(16) | Y | `INS-` + 7 digits | PK (per run) | |
| user_id | VARCHAR(16) | Y | | | FK |
| month | VARCHAR(7) | Y | local month | | |
| insight_type | VARCHAR(32) | Y | OVER_BUDGET, NEAR_BUDGET, MISSED_RECURRING, RECURRING_PRICE_CHANGE, SPENDING_SPIKE | | |
| category_code | VARCHAR(16) | N | taxonomy code (budget / spike insights) | | FK → category |
| actual_amount | NUMERIC(14,2) | Y | USD spend for the month | | **derived** from classified transactions |
| reference_amount | NUMERIC(14,2) | Y | USD budget, series amount or monthly average | | |
| severity | VARCHAR(8) | Y | LOW, MEDIUM, HIGH (OVER_BUDGET > 125 % = HIGH) | | |
| evidence | VARCHAR(400) | N | `budget_id=…;utilization=…` or `series_id=…` | | traceable to source records |

## Pipeline output and audit tables

| Table | Key | Purpose |
|---|---|---|
| `pipeline_run` | run_id `RUN-000001` | status, actor, timings, counts, `metrics_json` (evaluation), error |
| `transaction_classification` | (run_id, transaction_id) | category, method (`RULE_*`, `TRANSFER_PAIR`, `REFUND_LINK`, `REFUND_UNLINKED`, `FALLBACK`), rule_id, confidence 0–1, transfer/refund flags and links, recurring_series_id, local_date, budget_month, `spend_impact` (USD, + = spend, − = refund), reason |
| `transfer_link` | (run_id, group_id `TRF-…`) | out leg, in leg, kind XFER/CCPY, lag days, amount |
| `refund_link` | (run_id, refund_transaction_id) | original purchase, amount, lag days |
| `recurring_series_member` | (run_id, series_id, transaction_id) | N:M bridge |
| `budget_comparison` | (run_id, budget_id) | supplied/resolved code, resolution EXACT/NORMALIZED/ALIAS/UNMAPPED, budget, actual, variance, utilization, status UNDER/NEAR/OVER/UNMAPPED |
| `exception_record` | id; unique (run_id, exception_id `EXC-…`) | type, severity, entity, user, detail, status, assignee |
| `exception_event` | event_id | from/to status, actor, note, timestamp |
| `budget_event` | event_id | old/new amount, actor, reason, timestamp |
| `dataset_load` | load_id | source path, actor, timestamp, row counts |

## Ground-truth files (`data/<scale>/ground_truth/`, never loaded into the engine)

| File | Key | Fields |
|---|---|---|
| `transaction_labels.csv` | transaction_id | true_category_code, is_transfer, transfer_group_id (`TRG-…`, both legs share it), is_refund, refund_of_transaction_id, recurring_series_id, local_date, budget_month, issue_type (`|`-separated: NONE, TRANSFER_DOUBLE_COUNT, REFUND_AS_NEW_EXPENSE, RECURRING_FALSE_SKIP, TIMEZONE_BOUNDARY), expected_decision (CATEGORIZE, EXCLUDE_FROM_SPEND, OFFSET_ORIGINAL_CATEGORY, ASSIGN_LOCAL_MONTH, MATCH_TO_NEXT_DUE_DATE) |
| `recurring_series.csv` | series_id | see RecurringSeries above |
| `budget_labels.csv` | budget_id | supplied_category_code, expected_category_code (or UNMAPPED), issue_type (NONE / BUDGET_TAXONOMY_MISMATCH), expected_decision (USE_AS_IS, MAP_TO_<code>, FLAG_UNMAPPED) |
| `monthly_actuals.csv` | (user_id, month, category_code) | true spend (USD) and transaction count, derived from labels |
| `insights.csv` | insight_id | expected insights computed from true actuals with the same thresholds as the engine |

### How edge cases are labelled (documented rules, not random labels)

| issue_type | Injection rule | Label rule |
|---|---|---|
| TRANSFER_DOUBLE_COUNT | every credit-card payment pair (30 % with an opaque `WEB PMT FSB CARD …` descriptor, posted 1 day apart) and 15 % of savings transfers (opaque `WEB PMT` / `ACH CREDIT REF` descriptors, inbound leg 1–2 business days later) | both legs |
| REFUND_AS_NEW_EXPENSE | 25 % of refunds are posted with `txn_type = CARD_PURCHASE` and the purchase descriptor (no "REFUND" text) | the refund row |
| RECURRING_FALSE_SKIP | 12 % of rent series: one month's rent posts on the previous month's last business day | the moved row + series |
| RECURRING_TRUE_SKIP | 10 % of subscription series (≥ 4 months long): one interior month has no charge | series (`missed_dates`) |
| TIMEZONE_BOUNDARY | 3 purchases per user moved to 21:00–23:59 local on month-end (UTC−) or 00:00–04:59 on the 1st (UTC+); plus naturally occurring overnight subscription charges | any row whose local month ≠ UTC month |
| BUDGET_TAXONOMY_MISMATCH | 4 % of (user, category) budget lines use a legacy/free-text code; 3 % of users add an unmappable category (PETS, HOBBY, CHILDCARE) | every affected monthly budget row |
