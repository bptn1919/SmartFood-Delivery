-- ===========================================================================
-- payment module — mirrors ../../backend/payment/models.py
-- (PaymentTransaction/State/Event, CustomerPaymentInfo, InternalWallet,
--  WalletTransaction/State, WithdrawalFailureLog, SettlementRecord,
--  PayoutLedger, ChefCODBalance) + migration 0012's append-only trigger.
--
-- Table names follow Django's explicit Meta.db_table values (all of them set
-- one), which keeps SQL in the Django docs (PAYMENT_WALLET_INTERVIEW_PREP.md,
-- WALLET_SECURITY_DOCS.md) directly readable against this schema.
-- ===========================================================================

-- Django: PaymentTransaction (auto integer PK + uid). checkout: OneToOne, CASCADE.
CREATE TABLE payment_transactions (
    id                  BIGSERIAL PRIMARY KEY,
    uid                 UUID NOT NULL UNIQUE,
    checkout_uid        UUID NOT NULL UNIQUE REFERENCES checkout (uid) ON DELETE CASCADE,
    payment_method      VARCHAR(20) NOT NULL DEFAULT 'COD',
    -- legacy VNPay columns: present in Django's model, written by nothing on a live path
    vnp_txn_ref         VARCHAR(100),
    vnp_transaction_no  VARCHAR(100),
    vnp_bank_code       VARCHAR(50),
    vnp_card_type       VARCHAR(50),
    payos_order_code    BIGINT,
    amount              NUMERIC(15, 2) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_tx_vnp_txn_ref ON payment_transactions (vnp_txn_ref);
CREATE INDEX idx_payment_tx_payos_order_code ON payment_transactions (payos_order_code);

-- Django: PaymentTransactionState — OneToOne(PaymentTransaction, CASCADE)
CREATE TABLE payment_transaction_states (
    id                      BIGSERIAL PRIMARY KEY,
    payment_transaction_id  BIGINT NOT NULL UNIQUE REFERENCES payment_transactions (id) ON DELETE CASCADE,
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    transaction_id          VARCHAR(100),
    payment_url             TEXT,
    payos_payment_link_id   VARCHAR(100),
    paid_at                 TIMESTAMPTZ,
    gateway_response        JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX payment_tx_state_status_idx ON payment_transaction_states (status);
CREATE INDEX payment_tx_state_paid_at_idx ON payment_transaction_states (paid_at);

-- Django: PaymentTransactionEvent — append-only audit log with an HMAC hash chain
CREATE TABLE payment_transaction_events (
    id                      BIGSERIAL PRIMARY KEY,
    payment_transaction_id  BIGINT NOT NULL REFERENCES payment_transactions (id) ON DELETE CASCADE,
    event_type              VARCHAR(40) NOT NULL,
    status_from             VARCHAR(20),
    status_to               VARCHAR(20),
    payload                 JSONB,
    signature_valid         BOOLEAN,
    source                  VARCHAR(40) NOT NULL DEFAULT '',
    previous_hash           VARCHAR(64) NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000',
    chain_hash              VARCHAR(64) NOT NULL DEFAULT '',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX payment_tx_event_tx_idx ON payment_transaction_events (payment_transaction_id, created_at);
CREATE INDEX payment_tx_event_type_idx ON payment_transaction_events (event_type);

-- Django: CustomerPaymentInfo — OneToOne(User, CASCADE)
CREATE TABLE customer_payment_info (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              BIGINT NOT NULL UNIQUE REFERENCES app_user (id) ON DELETE CASCADE,
    bank_name            VARCHAR(255) NOT NULL,
    bank_code            VARCHAR(20) NOT NULL,
    bank_account_number  VARCHAR(50) NOT NULL,
    bank_account_name    VARCHAR(255) NOT NULL,
    bank_branch          VARCHAR(255),
    is_verified          BOOLEAN NOT NULL DEFAULT TRUE,
    verified_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Django: InternalWallet — OneToOne(User, CASCADE). signature = HMAC(user_id:balance:pending)
CREATE TABLE internal_wallets (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT NOT NULL UNIQUE REFERENCES app_user (id) ON DELETE CASCADE,
    balance          NUMERIC(15, 2) NOT NULL DEFAULT 0,
    pending_balance  NUMERIC(15, 2) NOT NULL DEFAULT 0,
    currency         VARCHAR(8) NOT NULL DEFAULT 'VND',
    signature        VARCHAR(64) NOT NULL DEFAULT '',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Django: WalletTransaction — the per-user tamper-evident ledger.
-- order: FK(Order, SET_NULL, null=True). NOTE: the append-only trigger below also
-- blocks the UPDATE that ON DELETE SET NULL would issue, i.e. an order that has
-- wallet rows can no longer be deleted — same as Django (its Collector issues the
-- same UPDATE and the same trigger rejects it).
CREATE TABLE wallet_transactions (
    id                BIGSERIAL PRIMARY KEY,
    uid               UUID NOT NULL UNIQUE,
    user_id           BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    order_uid         UUID REFERENCES "order" (uid) ON DELETE SET NULL,
    transaction_type  VARCHAR(20) NOT NULL,
    amount            NUMERIC(15, 2) NOT NULL,
    reference_id      VARCHAR(120),
    balance_before    NUMERIC(15, 2) NOT NULL DEFAULT 0,
    balance_after     NUMERIC(15, 2) NOT NULL DEFAULT 0,
    previous_hash     VARCHAR(64) NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000',
    chain_hash        VARCHAR(64) NOT NULL DEFAULT '',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_wallet_tx_user_created ON wallet_transactions (user_id, created_at);
CREATE INDEX wallet_tx_type_idx ON wallet_transactions (transaction_type);

-- Django migration 0012_wallet_transaction_immutable_trigger — copied verbatim.
CREATE OR REPLACE FUNCTION prevent_wallet_transaction_modification()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'wallet_transactions is append-only: UPDATE and DELETE are not permitted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER no_wallet_transaction_update
BEFORE UPDATE ON wallet_transactions
FOR EACH ROW EXECUTE FUNCTION prevent_wallet_transaction_modification();

CREATE TRIGGER no_wallet_transaction_delete
BEFORE DELETE ON wallet_transactions
FOR EACH ROW EXECUTE FUNCTION prevent_wallet_transaction_modification();

-- Django: WalletTransactionState — the mutable half (PENDING/SUCCESS/FAILED)
CREATE TABLE wallet_transaction_states (
    id                     BIGSERIAL PRIMARY KEY,
    wallet_transaction_id  BIGINT NOT NULL UNIQUE REFERENCES wallet_transactions (id) ON DELETE CASCADE,
    status                 VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    description            TEXT NOT NULL DEFAULT '',
    metadata               JSONB,
    payout_id              VARCHAR(120),
    processed_at           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX wallet_tx_state_status_idx ON wallet_transaction_states (status);
CREATE INDEX wallet_tx_state_processed_idx ON wallet_transaction_states (processed_at);

-- Django: WithdrawalFailureLog (db_table wallet_withdrawal_failures)
CREATE TABLE wallet_withdrawal_failures (
    id                     BIGSERIAL PRIMARY KEY,
    uid                    UUID NOT NULL UNIQUE,
    user_id                BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    wallet_transaction_id  BIGINT REFERENCES wallet_transactions (id) ON DELETE SET NULL,
    amount                 NUMERIC(15, 2) NOT NULL DEFAULT 0,
    stage                  VARCHAR(40) NOT NULL,
    error_type             VARCHAR(120) NOT NULL DEFAULT '',
    error_message          TEXT NOT NULL DEFAULT '',
    metadata               JSONB,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_withdrawal_failure_user_created ON wallet_withdrawal_failures (user_id, created_at);
CREATE INDEX idx_withdrawal_failure_stage ON wallet_withdrawal_failures (stage);

-- Django: SettlementRecord — OneToOne(Order, CASCADE); chef FK(User, SET_NULL).
-- status is VARCHAR(20) with no CHECK: Django writes 'CANCELLED', which is not in
-- its own SETTLEMENT_STATUS_CHOICES (choices are not enforced by the DB there either).
CREATE TABLE settlement_records (
    id                  BIGSERIAL PRIMARY KEY,
    uid                 UUID NOT NULL UNIQUE,
    order_uid           UUID NOT NULL UNIQUE REFERENCES "order" (uid) ON DELETE CASCADE,
    chef_id             BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    gross_amount        NUMERIC(15, 2) NOT NULL DEFAULT 0,
    platform_fee        NUMERIC(15, 2) NOT NULL DEFAULT 0,
    chef_payout_amount  NUMERIC(15, 2) NOT NULL DEFAULT 0,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payment_method      VARCHAR(20) NOT NULL DEFAULT 'COD',
    payout_id           VARCHAR(100),
    payout_reference    VARCHAR(100),
    settlement_data     JSONB,
    error_reason        TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at          TIMESTAMPTZ
);

CREATE INDEX idx_settlement_chef_status ON settlement_records (chef_id, status);
CREATE INDEX idx_settlement_method_status ON settlement_records (payment_method, status);
CREATE INDEX idx_settlement_created ON settlement_records (created_at);

-- Django: PayoutLedger — FK(SettlementRecord, CASCADE), NOT NULL.
CREATE TABLE payout_ledger (
    id                    BIGSERIAL PRIMARY KEY,
    uid                   UUID NOT NULL UNIQUE,
    settlement_record_id  BIGINT NOT NULL REFERENCES settlement_records (id) ON DELETE CASCADE,
    ledger_type           VARCHAR(20) NOT NULL,
    amount                NUMERIC(15, 2) NOT NULL,
    description           TEXT NOT NULL,
    order_uid             VARCHAR(100) NOT NULL,
    -- Django: IntegerField (a plain id copy, not an FK); BIGINT here to hold app_user ids.
    chef_id               BIGINT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payout_ledger_type_created ON payout_ledger (ledger_type, created_at);
CREATE INDEX idx_payout_ledger_chef_created ON payout_ledger (chef_id, created_at);

-- Django: ChefCODBalance — OneToOne(User, CASCADE)
CREATE TABLE chef_cod_balance (
    id                      BIGSERIAL PRIMARY KEY,
    chef_id                 BIGINT NOT NULL UNIQUE REFERENCES app_user (id) ON DELETE CASCADE,
    unsettled_balance       NUMERIC(15, 2) NOT NULL DEFAULT 0,
    unsettled_orders_count  INTEGER NOT NULL DEFAULT 0,
    total_settled           NUMERIC(15, 2) NOT NULL DEFAULT 0,
    last_settlement_at      TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
