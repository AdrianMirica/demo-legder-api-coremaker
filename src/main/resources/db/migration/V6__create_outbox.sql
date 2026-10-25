CREATE TABLE outbox_events (
    id CHAR(36) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    routing_key VARCHAR(128) NOT NULL,
    payload JSON NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_outbox_transaction
        FOREIGN KEY (aggregate_id) REFERENCES ledger_transactions(id)
);

CREATE INDEX idx_outbox_unpublished
    ON outbox_events (published_at, created_at, id);
