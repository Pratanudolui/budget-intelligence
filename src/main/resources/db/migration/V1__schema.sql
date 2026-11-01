-- =====================================================================================
-- Budget Intelligence schema. Column names match the CSV headers exactly so the seed
-- files load with COPY ... (FORMAT csv, HEADER true).
--   * master / input tables : loaded from data/<scale>/input/*.csv
--   * run output tables      : appended per pipeline run (keyed by run_id, never overwritten)
--   * event tables           : state changes recorded as rows (exception_event, budget_event)
-- Ground-truth labels are intentionally NOT stored here (kept in data/<scale>/ground_truth).
-- =====================================================================================

-- ------------------------------------------------------------------ master data
CREATE TABLE category (
    category_code   VARCHAR(16)  PRIMARY KEY,
    name            VARCHAR(80)  NOT NULL,
    parent_code     VARCHAR(16)  REFERENCES category (category_code),
    kind            VARCHAR(16)  NOT NULL CHECK (kind IN ('GROUP', 'INCOME', 'EXPENSE', 'TRANSFER', 'UNKNOWN')),
    essential       BOOLEAN      NOT NULL,
    effective_from  DATE         NOT NULL,
    status          VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'DEPRECATED'))
);

CREATE TABLE category_alias (
    alias_code      VARCHAR(40)  PRIMARY KEY,
    category_code   VARCHAR(16)  NOT NULL REFERENCES category (category_code)
);

CREATE TABLE merchant (
    merchant_id            VARCHAR(16)  PRIMARY KEY,
    canonical_name         VARCHAR(120) NOT NULL,
    merchant_type          VARCHAR(20)  NOT NULL,
    default_category_code  VARCHAR(16)  REFERENCES category (category_code),
    mcc                    VARCHAR(4),
    status                 VARCHAR(16)  NOT NULL
);

-- Bridge table: Merchant N:M Category
CREATE TABLE merchant_category (
    merchant_id    VARCHAR(16) NOT NULL REFERENCES merchant (merchant_id),
    category_code  VARCHAR(16) NOT NULL REFERENCES category (category_code),
    is_primary     BOOLEAN     NOT NULL,
    PRIMARY KEY (merchant_id, category_code)
);

CREATE TABLE category_rule (
    rule_id         VARCHAR(16)   PRIMARY KEY,
    priority        INTEGER       NOT NULL,
    match_type      VARCHAR(20)   NOT NULL CHECK (match_type IN ('TXN_TYPE', 'DESCRIPTOR_REGEX', 'MERCHANT', 'MERCHANT_AMOUNT', 'MCC')),
    merchant_id     VARCHAR(16)   REFERENCES merchant (merchant_id),
    pattern         VARCHAR(200),
    mcc             VARCHAR(4),
    txn_type        VARCHAR(20),
    min_abs_amount  NUMERIC(14, 2),
    max_abs_amount  NUMERIC(14, 2),
    category_code   VARCHAR(16)   NOT NULL REFERENCES category (category_code),
    effective_from  DATE          NOT NULL,
    effective_to    DATE,
    status          VARCHAR(16)   NOT NULL,
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE TABLE user_profile (
    user_id         VARCHAR(16)   PRIMARY KEY CHECK (user_id ~ '^USR-[0-9]{6}$'),
    display_name    VARCHAR(80)   NOT NULL,
    household_size  INTEGER       NOT NULL CHECK (household_size BETWEEN 1 AND 10),
    timezone        VARCHAR(40)   NOT NULL,
    income_band     VARCHAR(8)    NOT NULL CHECK (income_band IN ('LOW', 'MID', 'HIGH')),
    annual_income   NUMERIC(14, 2) NOT NULL,
    city            VARCHAR(40)   NOT NULL,
    created_on      DATE          NOT NULL,
    status          VARCHAR(16)   NOT NULL
);

CREATE TABLE account (
    account_id       VARCHAR(24)   PRIMARY KEY CHECK (account_id ~ '^ACC-USR-[0-9]{6}-[0-9]{2}$'),
    user_id          VARCHAR(16)   NOT NULL REFERENCES user_profile (user_id),
    account_type     VARCHAR(16)   NOT NULL CHECK (account_type IN ('CHECKING', 'SAVINGS', 'CREDIT_CARD')),
    opened_on        DATE          NOT NULL,
    closed_on        DATE,
    status           VARCHAR(16)   NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    currency         VARCHAR(3)    NOT NULL,
    opening_balance  NUMERIC(14, 2) NOT NULL,
    closing_balance  NUMERIC(14, 2) NOT NULL,
    CHECK ((status = 'OPEN') = (closed_on IS NULL))
);
CREATE INDEX ix_account_user ON account (user_id);

CREATE TABLE ledger_transaction (
    transaction_id  VARCHAR(24)    PRIMARY KEY CHECK (transaction_id ~ '^TXN-[0-9]{4}-[0-9]{8}$'),
    account_id      VARCHAR(24)    NOT NULL REFERENCES account (account_id),
    merchant_id     VARCHAR(16)    REFERENCES merchant (merchant_id),
    posted_at_utc   TIMESTAMPTZ    NOT NULL,
    amount          NUMERIC(14, 2) NOT NULL CHECK (amount <> 0),
    currency        VARCHAR(3)     NOT NULL,
    descriptor      VARCHAR(120)   NOT NULL,
    txn_type        VARCHAR(20)    NOT NULL,
    mcc             VARCHAR(4),
    balance_after   NUMERIC(14, 2) NOT NULL
);
CREATE INDEX ix_txn_account_posted ON ledger_transaction (account_id, posted_at_utc);

-- category_code is stored as supplied (may not match the taxonomy, see BUDGET_TAXONOMY_MISMATCH) so it has no FK.
CREATE TABLE budget (
    budget_id       VARCHAR(64)    PRIMARY KEY,
    user_id         VARCHAR(16)    NOT NULL REFERENCES user_profile (user_id),
    budget_month    VARCHAR(7)     NOT NULL CHECK (budget_month ~ '^[0-9]{4}-[0-9]{2}$'),
    category_code   VARCHAR(40)    NOT NULL,
    budget_amount   NUMERIC(14, 2) NOT NULL CHECK (budget_amount > 0),
    currency        VARCHAR(3)     NOT NULL,
    effective_from  DATE           NOT NULL,
    status          VARCHAR(16)    NOT NULL
);
CREATE INDEX ix_budget_user_month ON budget (user_id, budget_month);

-- Budget amount changes are appended here; the budget row holds the current value, this table the history.
CREATE TABLE budget_event (
    event_id     BIGSERIAL      PRIMARY KEY,
    budget_id    VARCHAR(64)    NOT NULL REFERENCES budget (budget_id),
    event_type   VARCHAR(24)    NOT NULL,
    old_amount   NUMERIC(14, 2),
    new_amount   NUMERIC(14, 2) NOT NULL,
    actor        VARCHAR(60)    NOT NULL,
    reason       VARCHAR(200),
    created_at   TIMESTAMPTZ    NOT NULL
);

-- Audit of dataset loads (which files, how many rows, when)
CREATE TABLE dataset_load (
    load_id       BIGSERIAL     PRIMARY KEY,
    source_path   VARCHAR(400)  NOT NULL,
    loaded_by     VARCHAR(60)   NOT NULL,
    loaded_at     TIMESTAMPTZ   NOT NULL,
    row_counts    TEXT          NOT NULL
);

-- ------------------------------------------------------------------ pipeline runs and outputs
CREATE TABLE pipeline_run (
    run_id           VARCHAR(16)  PRIMARY KEY,
    status           VARCHAR(16)  NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    triggered_by     VARCHAR(60)  NOT NULL,
    started_at       TIMESTAMPTZ  NOT NULL,
    finished_at      TIMESTAMPTZ,
    transactions     INTEGER,
    exceptions       INTEGER,
    insights         INTEGER,
    recurring_series INTEGER,
    metrics_json     TEXT,
    error_message    VARCHAR(1000)
);

CREATE TABLE transaction_classification (
    run_id                   VARCHAR(16)    NOT NULL REFERENCES pipeline_run (run_id),
    transaction_id           VARCHAR(24)    NOT NULL REFERENCES ledger_transaction (transaction_id),
    user_id                  VARCHAR(16)    NOT NULL REFERENCES user_profile (user_id),
    category_code            VARCHAR(16)    NOT NULL REFERENCES category (category_code),
    method                   VARCHAR(32)    NOT NULL,
    rule_id                  VARCHAR(16)    REFERENCES category_rule (rule_id),
    confidence               NUMERIC(4, 2)  NOT NULL,
    is_transfer              BOOLEAN        NOT NULL,
    transfer_group_id        VARCHAR(16),
    is_refund                BOOLEAN        NOT NULL,
    refund_of_transaction_id VARCHAR(24)    REFERENCES ledger_transaction (transaction_id),
    recurring_series_id      VARCHAR(16),
    local_date               DATE           NOT NULL,
    budget_month             VARCHAR(7)     NOT NULL,
    spend_impact             NUMERIC(14, 2) NOT NULL,
    reason                   VARCHAR(200),
    PRIMARY KEY (run_id, transaction_id)
);
CREATE INDEX ix_cls_user_month ON transaction_classification (run_id, user_id, budget_month);

CREATE TABLE transfer_link (
    run_id              VARCHAR(16)    NOT NULL REFERENCES pipeline_run (run_id),
    group_id            VARCHAR(16)    NOT NULL,
    out_transaction_id  VARCHAR(24)    NOT NULL REFERENCES ledger_transaction (transaction_id),
    in_transaction_id   VARCHAR(24)    NOT NULL REFERENCES ledger_transaction (transaction_id),
    kind                VARCHAR(8)     NOT NULL,
    lag_days            INTEGER        NOT NULL,
    amount              NUMERIC(14, 2) NOT NULL,
    PRIMARY KEY (run_id, group_id)
);

CREATE TABLE refund_link (
    run_id                   VARCHAR(16)    NOT NULL REFERENCES pipeline_run (run_id),
    refund_transaction_id    VARCHAR(24)    NOT NULL REFERENCES ledger_transaction (transaction_id),
    original_transaction_id  VARCHAR(24)    NOT NULL REFERENCES ledger_transaction (transaction_id),
    amount                   NUMERIC(14, 2) NOT NULL,
    lag_days                 INTEGER        NOT NULL,
    PRIMARY KEY (run_id, refund_transaction_id)
);

CREATE TABLE recurring_series (
    run_id             VARCHAR(16)    NOT NULL REFERENCES pipeline_run (run_id),
    series_id          VARCHAR(16)    NOT NULL,
    user_id            VARCHAR(16)    NOT NULL REFERENCES user_profile (user_id),
    account_id         VARCHAR(24)    NOT NULL REFERENCES account (account_id),
    merchant_id        VARCHAR(16)    NOT NULL REFERENCES merchant (merchant_id),
    kind               VARCHAR(20)    NOT NULL,
    cadence            VARCHAR(12)    NOT NULL CHECK (cadence IN ('WEEKLY', 'BIWEEKLY', 'MONTHLY')),
    typical_amount     NUMERIC(14, 2) NOT NULL,
    first_date         DATE           NOT NULL,
    last_date          DATE           NOT NULL,
    occurrences        INTEGER        NOT NULL,
    status             VARCHAR(12)    NOT NULL CHECK (status IN ('ACTIVE', 'ENDED')),
    missed_dates       VARCHAR(400),
    price_change_date  DATE,
    PRIMARY KEY (run_id, series_id)
);

-- Bridge table: RecurringSeries N:M Transaction (per run)
CREATE TABLE recurring_series_member (
    run_id          VARCHAR(16) NOT NULL,
    series_id       VARCHAR(16) NOT NULL,
    transaction_id  VARCHAR(24) NOT NULL REFERENCES ledger_transaction (transaction_id),
    PRIMARY KEY (run_id, series_id, transaction_id),
    FOREIGN KEY (run_id, series_id) REFERENCES recurring_series (run_id, series_id)
);

CREATE TABLE budget_comparison (
    run_id          VARCHAR(16)    NOT NULL REFERENCES pipeline_run (run_id),
    budget_id       VARCHAR(64)    NOT NULL REFERENCES budget (budget_id),
    user_id         VARCHAR(16)    NOT NULL REFERENCES user_profile (user_id),
    budget_month    VARCHAR(7)     NOT NULL,
    supplied_code   VARCHAR(40)    NOT NULL,
    resolved_code   VARCHAR(16)    REFERENCES category (category_code),
    resolution      VARCHAR(16)    NOT NULL CHECK (resolution IN ('EXACT', 'NORMALIZED', 'ALIAS', 'UNMAPPED')),
    budget_amount   NUMERIC(14, 2) NOT NULL,
    actual_amount   NUMERIC(14, 2) NOT NULL,
    variance        NUMERIC(14, 2) NOT NULL,
    utilization     NUMERIC(8, 4)  NOT NULL,
    status          VARCHAR(12)    NOT NULL CHECK (status IN ('UNDER', 'NEAR', 'OVER', 'UNMAPPED')),
    PRIMARY KEY (run_id, budget_id)
);
CREATE INDEX ix_bc_user_month ON budget_comparison (run_id, user_id, budget_month);

CREATE TABLE insight (
    run_id            VARCHAR(16)    NOT NULL REFERENCES pipeline_run (run_id),
    insight_id        VARCHAR(16)    NOT NULL,
    user_id           VARCHAR(16)    NOT NULL REFERENCES user_profile (user_id),
    month             VARCHAR(7)     NOT NULL,
    insight_type      VARCHAR(32)    NOT NULL,
    category_code     VARCHAR(16)    REFERENCES category (category_code),
    actual_amount     NUMERIC(14, 2) NOT NULL,
    reference_amount  NUMERIC(14, 2) NOT NULL,
    severity          VARCHAR(8)     NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    evidence          VARCHAR(400),
    PRIMARY KEY (run_id, insight_id)
);
CREATE INDEX ix_insight_user ON insight (run_id, user_id, month);

CREATE TABLE exception_record (
    id              BIGSERIAL     PRIMARY KEY,
    run_id          VARCHAR(16)   NOT NULL REFERENCES pipeline_run (run_id),
    exception_id    VARCHAR(16)   NOT NULL,
    exception_type  VARCHAR(40)   NOT NULL,
    severity        VARCHAR(8)    NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    entity_type     VARCHAR(24)   NOT NULL,
    entity_id       VARCHAR(64)   NOT NULL,
    user_id         VARCHAR(16)   REFERENCES user_profile (user_id),
    detail          VARCHAR(400)  NOT NULL,
    status          VARCHAR(16)   NOT NULL CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED', 'DISMISSED')),
    assigned_to     VARCHAR(60),
    updated_at      TIMESTAMPTZ   NOT NULL,
    UNIQUE (run_id, exception_id)
);
CREATE INDEX ix_exc_status ON exception_record (run_id, status, exception_type);

-- Every exception status change is appended here (lifecycle history; never overwritten)
CREATE TABLE exception_event (
    event_id      BIGSERIAL    PRIMARY KEY,
    exception_pk  BIGINT       NOT NULL REFERENCES exception_record (id),
    from_status   VARCHAR(16),
    to_status     VARCHAR(16)  NOT NULL,
    actor         VARCHAR(60)  NOT NULL,
    note          VARCHAR(400),
    created_at    TIMESTAMPTZ  NOT NULL
);
