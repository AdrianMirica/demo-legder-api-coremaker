CREATE TABLE ledger_transactions (
    id CHAR(36) NOT NULL,
    ledger_sequence BIGINT NOT NULL AUTO_INCREMENT,
    description VARCHAR(255) NOT NULL,
    currency CHAR(3) NOT NULL,
    source VARCHAR(16) NOT NULL,
    source_reference VARCHAR(128) NULL,
    reversed_transaction_id CHAR(36) NULL,
    recorded_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ledger_sequence UNIQUE (ledger_sequence),
    CONSTRAINT fk_reversed_transaction
        FOREIGN KEY (reversed_transaction_id) REFERENCES ledger_transactions(id),
    CONSTRAINT uk_reversed_transaction UNIQUE (reversed_transaction_id)
);

CREATE TABLE ledger_entries (
    id CHAR(36) NOT NULL,
    transaction_id CHAR(36) NOT NULL,
    sequence_no INT NOT NULL,
    account_id CHAR(36) NOT NULL,
    direction VARCHAR(6) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    balance_after DECIMAL(19,2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_entry_transaction
        FOREIGN KEY (transaction_id) REFERENCES ledger_transactions(id),
    CONSTRAINT fk_entry_account
        FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT uk_transaction_sequence UNIQUE (transaction_id, sequence_no),
    CONSTRAINT chk_entry_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_entries_account_history
    ON ledger_entries (account_id, transaction_id, sequence_no);
CREATE INDEX idx_transactions_recorded
    ON ledger_transactions (recorded_at, ledger_sequence);
