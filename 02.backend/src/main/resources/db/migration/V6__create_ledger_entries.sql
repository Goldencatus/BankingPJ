CREATE TABLE ledger_entries (
    ledger_entry_id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    balance_after DECIMAL(19, 4) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_ledger_entries PRIMARY KEY (ledger_entry_id),
    CONSTRAINT fk_ledger_entries_account
        FOREIGN KEY (account_id) REFERENCES accounts (account_id)
);
