# PRD-002 - Persistent REST idempotency

## Objective

Guarantee at-most-once effects and replay the original HTTP result for every mutating REST request,
including when a retry reaches another application instance.

**Timebox:** 9 minutes  
**Prerequisite:** PRD-001  
**Out of scope:** account and transaction business logic

## Required behavior

- `Idempotency-Key` is mandatory on account creation, transaction recording, and reversal.
- The key is scoped by operation. Use these exact operation names:
  - `OPEN_ACCOUNT`
  - `RECORD_TRANSACTION`
  - `REVERSE_TRANSACTION:{transactionId}`
- The normalized request and operation are hashed with SHA-256.
- The first request stores its exact HTTP status and JSON body.
- An identical retry returns that stored status and body without calling business logic.
- The same operation and key with a different hash returns `409 IDEMPOTENCY_KEY_REUSED`.
- Two concurrent instances using the same key result in one business execution.
- An unexpected failure rolls back both the business change and idempotency row, allowing a retry.
- A domain rejection is stored and replayed, while leaving no partial business writes.

## Database migration

Create `V1__create_idempotency_records.sql`:

```sql
CREATE TABLE idempotency_records (
    operation VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    http_status SMALLINT NULL,
    response_body JSON NULL,
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (operation, idempotency_key)
);
```

There is deliberately no committed `PROCESSING` state. The row and business changes are written in
the same database transaction.

## Implementation

Create:

```text
src/main/java/ro/demo/ledger_api/idempotency/IdempotencyService.java
src/main/java/ro/demo/ledger_api/idempotency/IdempotencyRepository.java
src/main/java/ro/demo/ledger_api/idempotency/RequestHasher.java
src/main/java/ro/demo/ledger_api/idempotency/StoredHttpResponse.java
```

### Repository algorithm

Use `JdbcTemplate`; do not use JPA for the insert race:

1. Execute `INSERT IGNORE` with operation, key, hash, and current UTC timestamp.
2. If one row was inserted, this request owns the key.
3. If zero rows were inserted, select the existing row.
   MySQL waits for a concurrent uncommitted unique-key insert, so the select sees its final committed
   response. If the other transaction rolled back, retry the insert once.
4. Compare hashes with a constant-time comparison.
5. For the owner, execute business logic and update status/body/completed time before commit.

Run the entire algorithm and business callback inside a Spring `TransactionTemplate`. Do not place
`@Transactional` on a private or same-class method.

### Canonical hashing

- Hash `operation + "\n" + canonicalJson(request)`.
- Configure the dedicated Jackson writer to sort object properties and map keys.
- Canonicalize every `BigDecimal` with `stripTrailingZeros()` before serialization so numerically
  equal inputs such as `125.5` and `125.50` hash identically.
- Hash the validated DTO, not raw request bytes, so harmless JSON property ordering does not change
  the hash.
- Include the target transaction ID in reversal's operation string.

### Domain rejection handling

The callback returns `StoredHttpResponse`, not `ResponseEntity`.

- Convert successful DTOs to JSON before completing the record.
- Catch only `ApiException` inside the transaction, convert it to the same Problem Details body used
  by `GlobalExceptionHandler`, store that response, and commit.
- Let unexpected exceptions escape so the transaction rolls back.
- Controllers convert `StoredHttpResponse` to `ResponseEntity<String>` with the stored content type.

## Tests

Use MySQL Testcontainers for repository/integration tests:

1. First execution invokes the callback once and stores the response.
2. Sequential retry returns byte-equivalent status/body and does not invoke the callback.
3. Same key plus a changed request returns `IDEMPOTENCY_KEY_REUSED`.
4. Two threads with the same key and a blocking callback invoke the callback exactly once.
5. Unexpected callback failure leaves no idempotency row.
6. An `ApiException` response is persisted and replayed.

## Acceptance criteria

- [ ] Idempotency state is persistent and shared by all instances.
- [ ] Business state and the idempotency response have one transaction boundary.
- [ ] No in-memory cache or JVM lock is used for correctness.
- [ ] Concurrent duplicate execution is covered by a MySQL integration test.
- [ ] `./mvnw test` passes.

## Stop condition

Do not wire a real controller. Expose a tested service ready for PRD-003.
