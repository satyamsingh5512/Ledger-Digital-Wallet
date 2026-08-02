-- =====================================================================================
-- V1__init_schema.sql
-- Distributed Ledger & Digital Wallet — initial schema
--
-- Design principles applied here:
--   1. Money is NEVER stored as floating point. NUMERIC(19,4) gives us up to
--      999,999,999,999,999.9999 — enough headroom for any real-world currency unit,
--      with exact decimal arithmetic (no rounding drift across billions of transactions).
--   2. The ledger is APPEND-ONLY. We enforce this with a DB-level trigger that rejects
--      UPDATE/DELETE on ledger_entries, so even a bug or a rogue migration cannot rewrite
--      history. This is the same guarantee accounting systems and Stripe's own ledger rely on.
--   3. Every mutable, business-visible entity that competes for concurrent writes
--      (wallets.balance) carries a `version` column for optimistic locking to prevent
--      double-spend under concurrent transfers, instead of pessimistic row locks which
--      don't scale under high concurrency.
--   4. UUIDs (not sequential bigints) are used for all externally-referenceable primary
--      keys. This avoids leaking sequential IDs (enumeration/guessing risk) and allows
--      client-generated idempotent inserts without a round trip to fetch a generated ID.
-- =====================================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email               VARCHAR(255) NOT NULL,
    phone_number        VARCHAR(20),
    password_hash       VARCHAR(255) NOT NULL,
    full_name           VARCHAR(255) NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, SUSPENDED, DEACTIVATED
    role                VARCHAR(20)  NOT NULL DEFAULT 'USER',   -- USER, ADMIN
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE','SUSPENDED','DEACTIVATED')),
    CONSTRAINT chk_users_role CHECK (role IN ('USER','ADMIN'))
);

-- Case-insensitive lookups are the norm for login by email.
CREATE UNIQUE INDEX uq_users_email_lower ON users (LOWER(email));
CREATE INDEX idx_users_phone_number ON users (phone_number) WHERE phone_number IS NOT NULL;

COMMENT ON TABLE users IS 'Registered account holders. Authentication identity is separate from wallet ownership to allow one user to own multiple wallets (e.g. multi-currency) in future.';

-- ---------------------------------------------------------------------------
-- refresh_tokens
-- Stored server-side (hashed) so tokens can be revoked (logout / theft response)
-- instead of relying purely on short expiry, which is not sufficient alone.
-- ---------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash          VARCHAR(255) NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    revoked             BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

COMMENT ON TABLE refresh_tokens IS 'Hashed refresh tokens, allows server-side revocation independent of JWT expiry.';

-- ---------------------------------------------------------------------------
-- wallets
-- balance is a materialized, cached projection of the ledger — NOT the source
-- of truth. It exists so reads (the overwhelmingly common operation) don't
-- need to aggregate ledger_entries on every request. It is only ever mutated
-- in the same DB transaction that appends the corresponding ledger_entries,
-- under optimistic lock via `version`.
-- ---------------------------------------------------------------------------
CREATE TABLE wallets (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    currency            VARCHAR(3) NOT NULL,
    balance             NUMERIC(19,4) NOT NULL DEFAULT 0.0000,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, FROZEN, CLOSED
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_wallet_user_currency UNIQUE (user_id, currency),
    CONSTRAINT chk_wallet_status CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    CONSTRAINT chk_wallet_balance_nonnegative CHECK (balance >= 0)
);

CREATE INDEX idx_wallets_user_id ON wallets (user_id);

COMMENT ON TABLE wallets IS 'One wallet per (user, currency). balance is a cached/materialized projection of ledger_entries, protected by optimistic locking (version) to prevent lost updates under concurrent transfers.';
COMMENT ON COLUMN wallets.balance IS 'Derived value = SUM(ledger_entries.amount signed by entry_type) for this wallet. Never mutated independently of a ledger append in the same transaction.';
COMMENT ON COLUMN wallets.version IS 'Optimistic lock token (JPA @Version). Every balance mutation increments this; concurrent conflicting writes fail fast with OptimisticLockException and are retried.';

-- ---------------------------------------------------------------------------
-- transactions
-- The business-level record of an operation (transfer / credit / debit / refund).
-- One transaction produces 1..N ledger_entries (2 for a transfer: debit + credit).
-- ---------------------------------------------------------------------------
CREATE TABLE transactions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference_id        VARCHAR(64) NOT NULL,          -- human-shareable reference (e.g. TXN-XXXX)
    type                VARCHAR(20) NOT NULL,           -- TRANSFER, CREDIT, DEBIT, REFUND
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING, COMPLETED, FAILED, REVERSED
    source_wallet_id    UUID REFERENCES wallets(id),
    destination_wallet_id UUID REFERENCES wallets(id),
    amount              NUMERIC(19,4) NOT NULL,
    currency            VARCHAR(3) NOT NULL,
    idempotency_key     VARCHAR(128),
    failure_reason      VARCHAR(500),
    metadata            JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_transactions_reference_id UNIQUE (reference_id),
    CONSTRAINT chk_transactions_type CHECK (type IN ('TRANSFER','CREDIT','DEBIT','REFUND')),
    CONSTRAINT chk_transactions_status CHECK (status IN ('PENDING','COMPLETED','FAILED','REVERSED')),
    CONSTRAINT chk_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_transactions_wallets CHECK (
        source_wallet_id IS NOT NULL OR destination_wallet_id IS NOT NULL
    )
);

CREATE INDEX idx_transactions_source_wallet_id ON transactions (source_wallet_id, created_at DESC);
CREATE INDEX idx_transactions_destination_wallet_id ON transactions (destination_wallet_id, created_at DESC);
CREATE INDEX idx_transactions_idempotency_key ON transactions (idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_transactions_status ON transactions (status);
CREATE INDEX idx_transactions_created_at ON transactions (created_at DESC);

COMMENT ON TABLE transactions IS 'Business-level record of a financial operation. Mutable (status transitions PENDING->COMPLETED/FAILED) unlike ledger_entries, because a transaction represents a workflow with retries, whereas ledger_entries represent settled accounting facts.';
COMMENT ON COLUMN transactions.metadata IS 'Free-form JSONB for client-supplied context (e.g. transfer note, invoice id) — indexed selectively via GIN only if query patterns demand it (not needed at current scale).';

-- ---------------------------------------------------------------------------
-- ledger_entries — the immutable, append-only source of truth.
-- Double-entry bookkeeping: every transaction produces balanced DEBIT/CREDIT
-- rows whose signed amounts sum to zero across the ledger as a whole.
-- ---------------------------------------------------------------------------
CREATE TABLE ledger_entries (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id      UUID NOT NULL REFERENCES transactions(id),
    wallet_id            UUID NOT NULL REFERENCES wallets(id),
    entry_type          VARCHAR(10) NOT NULL,     -- DEBIT, CREDIT
    amount              NUMERIC(19,4) NOT NULL,
    balance_after       NUMERIC(19,4) NOT NULL,   -- wallet balance snapshot immediately after this entry, for audit/replay
    currency            VARCHAR(3) NOT NULL,
    description         VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_ledger_entry_type CHECK (entry_type IN ('DEBIT','CREDIT')),
    CONSTRAINT chk_ledger_amount_positive CHECK (amount > 0)
);

-- Hot path: "get statement for wallet X ordered by time" and "get all entries for a transaction"
CREATE INDEX idx_ledger_entries_wallet_id ON ledger_entries (wallet_id, created_at DESC);
CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries (transaction_id);

COMMENT ON TABLE ledger_entries IS 'Immutable, append-only double-entry ledger. Source of truth for all balances. Enforced immutable via trg_ledger_entries_immutable trigger below — no UPDATE or DELETE is permitted at the database level, independent of application code correctness.';
COMMENT ON COLUMN ledger_entries.balance_after IS 'Snapshot of the wallet balance after this entry was applied. Enables point-in-time audit/replay without re-aggregating the whole ledger, and is a cheap invariant check (recompute vs wallets.balance).';

-- Enforce immutability at the database level: reject UPDATE and DELETE outright.
CREATE OR REPLACE FUNCTION fn_prevent_ledger_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only: % operation on id=% is not permitted', TG_OP, OLD.id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_no_update
    BEFORE UPDATE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_ledger_mutation();

CREATE TRIGGER trg_ledger_entries_no_delete
    BEFORE DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_ledger_mutation();

-- ---------------------------------------------------------------------------
-- idempotency_keys
-- Guarantees exactly-once processing semantics for client-initiated financial
-- operations (transfers, credits, debits, refunds) submitted over an
-- at-least-once transport (HTTP retries, mobile network flakiness).
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key     VARCHAR(128) NOT NULL,
    request_hash        VARCHAR(64) NOT NULL,      -- SHA-256 of the request body, detects key-reuse-with-different-payload
    endpoint            VARCHAR(255) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS', -- IN_PROGRESS, COMPLETED, FAILED
    response_status     INT,
    response_body       JSONB,
    transaction_id      UUID REFERENCES transactions(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);

COMMENT ON TABLE idempotency_keys IS 'Deduplicates client-submitted mutating requests. On retry with the same key, the stored response is replayed instead of re-executing the operation. request_hash guards against a client reusing a key with a different payload (which is treated as a client error, not a replay).';

-- ---------------------------------------------------------------------------
-- refunds
-- ---------------------------------------------------------------------------
CREATE TABLE refunds (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_transaction_id UUID NOT NULL REFERENCES transactions(id),
    refund_transaction_id   UUID REFERENCES transactions(id),
    amount              NUMERIC(19,4) NOT NULL,
    reason              VARCHAR(500),
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING, COMPLETED, FAILED
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_refunds_status CHECK (status IN ('PENDING','COMPLETED','FAILED')),
    CONSTRAINT chk_refunds_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_refunds_original_transaction_id ON refunds (original_transaction_id);

COMMENT ON TABLE refunds IS 'Tracks refund requests against an original transaction. A completed refund creates its own reversing transaction + ledger entries rather than mutating the original (immutability preserved end-to-end).';

-- ---------------------------------------------------------------------------
-- outbox_events — transactional outbox pattern.
-- Guarantees that a Kafka event is published if and only if the DB
-- transaction that caused it commits (atomicity across DB + broker without
-- distributed transactions / 2PC).
-- ---------------------------------------------------------------------------
CREATE TABLE outbox_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type      VARCHAR(50) NOT NULL,   -- WALLET, TRANSACTION, REFUND
    aggregate_id        UUID NOT NULL,
    event_type          VARCHAR(100) NOT NULL,  -- WalletCreated, MoneyTransferred, ...
    payload             JSONB NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING, PUBLISHED, FAILED
    retry_count         INT NOT NULL DEFAULT 0,
    error_message       VARCHAR(1000),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at        TIMESTAMPTZ,

    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING','PUBLISHED','FAILED'))
);

-- The outbox poller scans PENDING rows in creation order — this is the hot query.
CREATE INDEX idx_outbox_events_status_created_at ON outbox_events (status, created_at) WHERE status = 'PENDING';

COMMENT ON TABLE outbox_events IS 'Transactional outbox: written in the same DB transaction as the business change, then asynchronously polled and published to Kafka by a scheduler. Guarantees at-least-once delivery without 2PC. Consumers must be idempotent (event id is the dedup key).';

-- ---------------------------------------------------------------------------
-- updated_at maintenance trigger, shared across mutable tables
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_wallets_updated_at BEFORE UPDATE ON wallets FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_transactions_updated_at BEFORE UPDATE ON transactions FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_refunds_updated_at BEFORE UPDATE ON refunds FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
