CREATE TABLE transfers (
    transfer_id BIGINT NOT NULL AUTO_INCREMENT,
    from_account_id BIGINT NOT NULL,
    to_account_id BIGINT NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    CONSTRAINT pk_transfers PRIMARY KEY (transfer_id),
    CONSTRAINT fk_transfers_from_account
        FOREIGN KEY (from_account_id) REFERENCES accounts (account_id),
    CONSTRAINT fk_transfers_to_account
        FOREIGN KEY (to_account_id) REFERENCES accounts (account_id)
);

ALTER TABLE ledger_entries
    ADD COLUMN transfer_id BIGINT NULL,
    ADD CONSTRAINT fk_ledger_entries_transfer
        FOREIGN KEY (transfer_id) REFERENCES transfers (transfer_id);
