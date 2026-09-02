CREATE TABLE accounts (
    account_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    account_number VARCHAR(30) NOT NULL,
    balance DECIMAL(19, 4) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_accounts PRIMARY KEY (account_id),
    CONSTRAINT uk_accounts_account_number UNIQUE (account_number),
    CONSTRAINT fk_accounts_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
);
