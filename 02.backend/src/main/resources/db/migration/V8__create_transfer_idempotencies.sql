CREATE TABLE transfer_idempotencies (
    idempotency_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    transfer_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    CONSTRAINT pk_transfer_idempotencies PRIMARY KEY (idempotency_id),
    CONSTRAINT uq_transfer_idempotencies_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT uq_transfer_idempotencies_transfer UNIQUE (transfer_id),
    CONSTRAINT fk_transfer_idempotencies_user
        FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_transfer_idempotencies_transfer
        FOREIGN KEY (transfer_id) REFERENCES transfers (transfer_id)
);
