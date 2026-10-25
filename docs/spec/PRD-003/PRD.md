# PRD-003 - Open and read accounts

**Status:** DONE

## Objective

Implement idempotent account creation and retrieval with exact currency and overdraft rules.

**Timebox:** 7 minutes  
**Prerequisite:** PRD-002  
**Endpoints:** `POST /api/v1/accounts`, `GET /api/v1/accounts/{accountId}`

## Database migration

Create `V2__create_accounts.sql`:

```sql
CREATE TABLE accounts (
    id CHAR(36) NOT NULL,
    currency CHAR(3) NOT NULL,
    overdraft_limit DECIMAL(19,2) NULL,
    balance DECIMAL(19,2) NOT NULL,
    settlement_account BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_account_balance_scale CHECK (balance = ROUND(balance, 2)),
    CONSTRAINT chk_overdraft_non_negative
        CHECK (overdraft_limit IS NULL OR overdraft_limit >= 0)
);

ALTER TABLE accounts
    ADD COLUMN settlement_currency CHAR(3)
        GENERATED ALWAYS AS (
            CASE WHEN settlement_account THEN currency ELSE NULL END
        ) STORED,
    ADD UNIQUE INDEX uk_settlement_currency (settlement_currency);
```

The generated nullable column implements partial uniqueness: it restricts settlement accounts to one
per currency while allowing any number of normal accounts.

Seed these internal settlement accounts:

| Currency | ID | Overdraft |
|---|---|---|
| EUR | `00000000-0000-0000-0000-000000000978` | unlimited (`NULL`) |
| USD | `00000000-0000-0000-0000-000000000840` | unlimited (`NULL`) |
| JPY | `00000000-0000-0000-0000-000000000392` | unlimited (`NULL`) |

All start at zero and have `settlement_account = TRUE`.

## Implementation

Create under `ro.demo.ledger_api.account`:

```text
AccountEntity
AccountRepository
AccountService
AccountController
OpenAccountRequest
AccountResponse
AccountMapper
```

### Open account

- Require `Idempotency-Key`.
- Request contains `currency` and optional `overdraftLimit`, defaulting to zero.
- Reject unknown or duplicate JSON properties. When present, `overdraftLimit` must be a non-null
  JSON number; numeric strings, empty strings, booleans, arrays, and objects are invalid.
- Parse currency in this order:
  1. require exactly three uppercase ASCII letters and a real ISO 4217 currency; otherwise
     `INVALID_CURRENCY_CODE`;
  2. require EUR, USD, or JPY; otherwise `UNSUPPORTED_CURRENCY`.
- Overdraft limit must be non-negative and fit the currency scale.
- Overdraft limit must not exceed `99999999999999999.99`, the maximum of `DECIMAL(19,2)`.
  Values outside this range return `400 VALIDATION_FAILED` with an `overdraftLimit` violation.
- Create a random UUID, zero balance, finite overdraft, and `settlement_account = FALSE`.
- Return `201`, the account response, and `Location: /api/v1/accounts/{id}`.
- Execute through `IdempotencyService` with operation `OPEN_ACCOUNT`.

### Read account

- Return ID, currency, overdraft limit, current balance, settlement flag, and created timestamp.
- Return `404 ACCOUNT_NOT_FOUND` for an unknown UUID.
- Return `400 VALIDATION_FAILED` with an `accountId` violation for malformed account identifiers.
- Internal settlement accounts may be read. Represent their `overdraftLimit` as `null`.

### Entity rules

- Use field access and no public setters.
- Balance can only change through an intention-revealing public domain method `applyDelta`; expose no
  generic balance setter.
- Normalize persisted values to the currency scale.
- Do not use `@Version`; correctness will use explicit pessimistic locks.

## Tests

Integration tests with MySQL:

1. Open EUR account defaults to zero overdraft and zero balance.
2. Open account with a valid nonzero overdraft.
3. Retry with the same key returns the same account ID.
4. Same key and different body returns 409.
5. `EURO` and `ABC` return `INVALID_CURRENCY_CODE`.
6. `GBP` returns `UNSUPPORTED_CURRENCY`.
7. JPY overdraft `100.5` returns `INVALID_AMOUNT_SCALE`.
8. Read existing and unknown accounts.
9. Exactly one seeded settlement account exists per supported currency.

## Acceptance criteria

- [x] Account creation is idempotent across database-backed retries.
- [x] Public accounts always have a finite, non-negative overdraft.
- [x] Settlement accounts exist and cannot be created through the REST API.
- [x] Response behavior matches the OpenAPI contract after the documented nullable-field update.
- [x] `./mvnw test` passes.

## Implementation entry point and verification

`AccountController` exposes account creation and retrieval. Creation delegates to
`IdempotencyService` using `OPEN_ACCOUNT`; replay preserves the account ID and `Location` header.
`AccountService` validates ISO currency codes and currency-specific overdraft precision, and
`AccountEntity` has field access without setters. `V2__create_accounts.sql` seeds one unlimited
settlement account for each supported currency.

`AccountApiTests` verifies defaults, explicit limits and scale normalization, idempotency replay and
conflict behavior, invalid/unsupported currencies, missing idempotency headers, not-found responses,
nullable settlement overdrafts, and settlement seeding. All tests run against MySQL 8.4.
Review regression tests also cover monetary range boundaries, rejection replay, strict JSON types,
unknown/forbidden properties, and malformed account identifiers.

## Stop condition

Do not create transaction tables or ledger logic.
