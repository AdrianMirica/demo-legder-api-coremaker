# PRD-002 - Persistent REST idempotency

**Status:** DONE

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

### Persistence details

The implementation adds `response_body_text LONGTEXT` and `content_type VARCHAR(64)` to the schema
above. MySQL normalizes JSON whitespace, object ordering, and numeric representations; replay reads
the original text column to preserve the exact response bytes. `response_body` still validates and
stores the JSON document. Keys and operations use `utf8mb4_0900_bin` (case-sensitive, NO PAD) so
distinct keys do not collide under MySQL's default case-insensitive collation.

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
3. If zero rows were inserted, select the existing row with `FOR SHARE`.
   MySQL waits for a concurrent uncommitted unique-key insert, so the select sees its final committed
   response. This is a current read even under REPEATABLE READ; completed responses are immutable,
   so it does not upgrade the duplicate insert's shared lock to an exclusive lock.
   If the other transaction rolled back, retry the insert once.
4. Compare hashes with a constant-time comparison.
5. For the owner, execute business logic and update status/body/completed time before commit.

Run the entire algorithm and business callback inside a Spring `TransactionTemplate`. Do not place
`@Transactional` on a private or same-class method.

The service opens a `REQUIRES_NEW` transaction and owns the commit boundary. Business callbacks
participate in that transaction; they must not start independent transactions or call transactional
proxies that mark the whole transaction rollback-only on a domain rejection. Use the same
transaction manager/datasource for JDBC and JPA.

MySQL can still choose a deadlock victim during `INSERT IGNORE` if an owner rolls back while several
duplicates wait. Retry the **entire** `REQUIRES_NEW` transaction, including reservation, callback,
response persistence, and commit, only for MySQL error 1213 / SQLSTATE 40001. Allow at most three
total attempts, log each retry, and use a short jittered backoff outside the transaction. Propagate
non-deadlock failures and the final failure unchanged. Callbacks must have transactional database
effects only; a rolled-back attempt may invoke them again. Never perform external side effects in
the callback (use the later transactional outbox).

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

Use a JDBC savepoint after the key reservation and before invoking the callback. On `ApiException`,
roll back to the savepoint and clear the JPA persistence context before saving the rejection. On
success, flush JPA changes before completing the response. This preserves the rejection record but
does not commit partial callback writes. Shared `ApiProblems` is the single Problem Details factory
used by both idempotency and `GlobalExceptionHandler`.

## Tests

Use MySQL Testcontainers for repository/integration tests:

1. First execution invokes the callback once and stores the response.
2. Sequential retry returns byte-equivalent status/body and does not invoke the callback.
3. Same key plus a changed request returns `IDEMPOTENCY_KEY_REUSED`.
4. Two threads with the same key and a blocking callback invoke the callback exactly once.
5. Unexpected callback failure leaves no idempotency row.
6. An `ApiException` response is persisted and replayed.
7. Two retries that both hold duplicate-insert shared locks replay a completed response without a
   lock-upgrade deadlock.
8. When an owner rolls back with two retries waiting on its actual MySQL lock, both retries succeed,
   with one committed business effect.
9. Deadlock retry starts a fresh transaction, is bounded, and does not retry unrelated errors.

## Acceptance criteria

- [x] Idempotency state is persistent and shared by all instances.
- [x] Business state and the idempotency response have one transaction boundary.
- [x] No in-memory cache or JVM lock is used for correctness.
- [x] Concurrent duplicate execution is covered by a MySQL integration test.
- [x] `./mvnw test` passes.

## Implementation entry point and verification

Call `IdempotencyService.execute(operation, key, validatedDto, callback)` from future controllers.
Build successful callback responses with `jsonResponse(HttpStatus.CREATED, responseDto)`.
Replay uses `StoredHttpResponse.status()`, `contentType()`, and `body()` without deserializing.
Domain rejections are converted to stored Problem Details responses by the service.

`IdempotencyServiceTests` covers persistence, cross-instance replay, payload conflicts, case-sensitive
and operation-scoped keys, unexpected-failure rollback, partial-write rollback on domain rejection,
decimal normalization, concurrent ownership, and ownership recovery after rollback.
The multi-retry tests coordinate duplicate shared locks before reads and observe actual InnoDB lock
waits before owner rollback. They assert deadlock-free completed replay and successful recovery from
a real reservation deadlock. `IdempotencyRetryTests` verifies fresh transaction attempts, full-operation
retry after a late deadlock, the three-attempt limit, and propagation of unrelated lock failures.
`RequestHasherTests` covers property/map ordering, nested decimals, exact precision, operation scope,
and array order. All integration tests run against MySQL 8.4 through Testcontainers.

## Stop condition

Do not wire a real controller. Expose a tested service ready for PRD-003.
