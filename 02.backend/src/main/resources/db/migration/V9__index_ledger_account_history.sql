CREATE INDEX idx_ledger_account_history
    ON ledger_entries (account_id, created_at DESC, ledger_entry_id DESC);
