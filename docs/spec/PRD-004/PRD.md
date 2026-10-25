# PRD-004 - Record balanced transactions

**Status:** DONE

## Objective

Atomically record immutable double-entry transactions while preserving exact balances under
concurrent requests.

**Timebox:** 17 minutes  
**Prerequisite:** PRD-003  
**Endpoints:** `POST /api/v1/transactions`, `GET /api/v1/transactions/{transactionId}`

## Database migration

Create `V3__create_ledger.sql`:

```sql
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
```

Create `V4__protect_immutable_ledger.sql` with MySQL triggers that reject `UPDATE` or `DELETE` on
`ledger_transactions` and `ledger_entries` using SQLSTATE `45000`. Inserts remain allowed. Account
balances remain mutable.

## Implementation

Create under `ro.demo.ledger_api.ledger`:

```text
LedgerTransactionEntity
LedgerEntryEntity
LedgerTransactionRepository
LedgerEntryRepository
AccountLockRepository
LedgerPostingService
TransactionController
RecordTransactionRequest
EntryRequest
TransactionResponse
EntryResponse
TransactionMapper
TransactionSource
```

### Write algorithm

Execute inside the transaction opened by `IdempotencyService`:

1. Validate description length and at least 2, at most 100 entries.
2. Validate every amount is positive before reading or writing the database.
3. Extract distinct account IDs, sort by UUID string, then lock with one native query:
   `SELECT * FROM accounts WHERE id IN (...) ORDER BY id FOR UPDATE`.
4. If the locked row count differs from distinct requested IDs, return `ACCOUNT_NOT_FOUND`.
5. Require all accounts to have the same currency.
6. Validate each amount against that currency's decimal scale.
7. Sum debits and credits with `BigDecimal`; compare using `compareTo`. Reject unequal totals with
   `UNBALANCED_TRANSACTION`.
8. Aggregate the signed delta per account (`CREDIT` positive, `DEBIT` negative).
9. For each finite-overdraft account, require:
   `currentBalance + aggregateDelta >= -overdraftLimit`.
10. Create transaction and entries in request order.
    The database-generated `ledger_sequence` is the deterministic ledger order used by history
    queries; it is internal and is not an API identifier.
11. Calculate each entry's `balanceAfter` using a per-account running balance in request order.
    Overdraft validation is based on the atomic final aggregate, not intermediate entry snapshots.
12. Set every account's final balance to current balance plus aggregate delta.
13. Flush once and commit everything together.

Never use read-modify-write without the `FOR UPDATE` locks. Never lock accounts one at a time.

### Read transaction

Return transaction metadata and entries ordered by `sequence_no ASC`. Return
`TRANSACTION_NOT_FOUND` when absent.

### REST behavior

- Require `Idempotency-Key` and use operation `RECORD_TRANSACTION`.
- Source is always `API` for this endpoint.
- Return `201` plus `Location: /api/v1/transactions/{id}`.
- Validation errors identify `entries[index].field` when applicable.
- JSON scalar types are strict: description and enum values are strings, account IDs are UUID
  strings, and amounts are JSON numbers. Scalar coercion, null values, unknown properties, and
  duplicate properties are rejected before idempotency hashing or ledger writes.

## Tests

Use MySQL integration tests:

1. Record the 125.50 EUR settlement example and verify both balances.
2. Record the three-entry payout example and verify all balances.
3. Reject one entry, zero/negative amount, too many decimals, unknown account, mixed currencies,
   unequal totals, and overdraft breach.
4. For every rejection, assert counts and balances are unchanged.
5. Retry returns the original transaction and creates no duplicate entries.
6. Two concurrent debits that together exceed the balance result in one success and one overdraft
   rejection, with no lost update.
7. Concurrent `A -> B` and `B -> A` complete within a timeout and do not deadlock.
8. Sum all account balances grouped by currency and assert each sum is zero.
9. Direct SQL update/delete of a transaction or entry is rejected by the trigger.

## Acceptance criteria

- [x] Transaction, entries, and balances commit all-or-nothing.
- [x] Account locks are acquired once in deterministic order.
- [x] No rejected transaction leaves a transaction, entry, or balance change.
- [x] Recorded transactions and entries are immutable in both application and database.
- [x] Concurrency and integrity tests pass against MySQL.
- [x] `./mvnw test` passes.

## Implementation entry point and verification

`TransactionController` exposes idempotent record and read endpoints.
`LedgerPostingService.recordApi` delegates to the shared `record` path with source `API`; later
payment and reversal PRDs can reuse that path without duplicating posting logic.
`AccountLockRepository` acquires every distinct affected account in one native `SELECT ... ORDER BY
id FOR UPDATE`. The posting service validates shape, account existence, currency, precision,
balance, storage range, and overdraft before scheduling immutable transaction and entry inserts and
managed account updates. `IdempotencyService` performs the single flush and transaction commit.

`LedgerTransactionApiTests` proves the settlement and payout examples, ordered entry snapshots,
atomic refusal for every specified invalid request, retry without duplicate rows, competing-debit
serialization, repeated opposite-direction transfers without deadlock, currency integrity, read
behavior, and database-trigger immutability. Tests run against MySQL 8.4, configured to permit
Flyway to create the immutability triggers while binary logging is enabled.

## Stop condition

Do not implement reversal, message consumption, or outbox publishing.
