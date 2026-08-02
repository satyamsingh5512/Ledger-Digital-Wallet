-- =====================================================================================
-- V3__add_missing_updated_at_columns.sql
--
-- BaseEntity (the shared JPA mapped-superclass for every entity) declares both
-- `created_at` and `updated_at` via Spring Data JPA auditing (@CreatedDate /
-- @LastModifiedDate), because a single shared audit contract across all entities is
-- simpler to reason about than special-casing "write-once" entities. V1 omitted the
-- `updated_at` column on refresh_tokens, ledger_entries, idempotency_keys, and
-- outbox_events on the assumption that those rows are never updated after insert —
-- but idempotency_keys and outbox_events ARE updated (status transitions), and Hibernate
-- schema validation requires the mapped column to exist on ledger_entries/refresh_tokens
-- regardless, since BaseEntity maps it unconditionally.
--
-- For ledger_entries specifically: this column will always equal created_at in practice
-- (the row is never updated at the application level, and the existing
-- trg_ledger_entries_no_update trigger would reject any attempt to change it) — it exists
-- purely to satisfy the shared BaseEntity contract, not because ledger_entries has
-- meaningful update semantics.
-- =====================================================================================

ALTER TABLE refresh_tokens ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE ledger_entries ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE idempotency_keys ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE outbox_events ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE TRIGGER trg_refresh_tokens_updated_at BEFORE UPDATE ON refresh_tokens FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_idempotency_keys_updated_at BEFORE UPDATE ON idempotency_keys FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_outbox_events_updated_at BEFORE UPDATE ON outbox_events FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

-- ledger_entries deliberately has NO updated_at trigger: the table's own
-- trg_ledger_entries_no_update trigger (from V1) already rejects any UPDATE outright,
-- so a "maintain updated_at on update" trigger on this table would never fire in practice.
