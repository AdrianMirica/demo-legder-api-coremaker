CREATE TABLE idempotency_records (
    operation VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    idempotency_key VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    request_hash CHAR(64) NOT NULL,
    http_status SMALLINT NULL,
    response_body JSON NULL,
    response_body_text LONGTEXT NULL,
    content_type VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (operation, idempotency_key)
);
