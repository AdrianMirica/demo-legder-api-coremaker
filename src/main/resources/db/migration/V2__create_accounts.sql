CREATE TABLE accounts (
    id CHAR(36) NOT NULL,
    currency CHAR(3) NOT NULL,
    overdraft_limit DECIMAL(19,2) NULL,
    balance DECIMAL(19,2) NOT NULL,
    settlement_account BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL,
    settlement_currency CHAR(3)
        GENERATED ALWAYS AS (
            CASE WHEN settlement_account THEN currency ELSE NULL END
        ) STORED,
    PRIMARY KEY (id),
    CONSTRAINT chk_account_balance_scale CHECK (balance = ROUND(balance, 2)),
    CONSTRAINT chk_overdraft_non_negative
        CHECK (overdraft_limit IS NULL OR overdraft_limit >= 0),
    UNIQUE INDEX uk_settlement_currency (settlement_currency)
);

INSERT INTO accounts
    (id, currency, overdraft_limit, balance, settlement_account, created_at)
VALUES
    ('00000000-0000-0000-0000-000000000978', 'EUR', NULL, 0, TRUE, UTC_TIMESTAMP(6)),
    ('00000000-0000-0000-0000-000000000840', 'USD', NULL, 0, TRUE, UTC_TIMESTAMP(6)),
    ('00000000-0000-0000-0000-000000000392', 'JPY', NULL, 0, TRUE, UTC_TIMESTAMP(6));
