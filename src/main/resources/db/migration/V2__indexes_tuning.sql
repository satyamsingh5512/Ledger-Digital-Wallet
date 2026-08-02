-- =====================================================================================
-- V2__indexes_tuning.sql
-- Secondary composite indexes added after modeling real query patterns from the
-- transaction-history and statement endpoints. Kept in a separate migration so the
-- baseline schema (V1) stays readable as the canonical structural definition, and so
-- index additions can be deployed independently (CREATE INDEX CONCURRENTLY in prod).
-- =====================================================================================

-- Transaction history is always filtered "for this wallet, most recent first, optionally
-- filtered by status/type" — covered by (wallet_id, created_at) already on both wallet FKs.
-- This composite additionally covers status-filtered history without a secondary lookup.
CREATE INDEX idx_transactions_source_status ON transactions (source_wallet_id, status, created_at DESC);
CREATE INDEX idx_transactions_destination_status ON transactions (destination_wallet_id, status, created_at DESC);

-- Refund lookups by status, e.g. a reconciliation job scanning for stuck PENDING refunds.
CREATE INDEX idx_refunds_status ON refunds (status, created_at);

-- Outbox failure investigation / alerting query: "show me FAILED events in the last day".
CREATE INDEX idx_outbox_events_status_failed ON outbox_events (status, created_at) WHERE status = 'FAILED';
