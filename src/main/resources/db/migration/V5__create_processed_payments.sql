CREATE TABLE processed_payments (
    payment_id VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    first_event_id CHAR(36) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    transaction_id CHAR(36) NULL,
    processed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (payment_id),
    CONSTRAINT fk_processed_payment_transaction
        FOREIGN KEY (transaction_id) REFERENCES ledger_transactions(id)
);
