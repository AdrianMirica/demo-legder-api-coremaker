# Ledger API PoC - implementation plan

This folder contains the implementation-ready PRDs derived from
[`docs/specs/requirements.md`](../specs/requirements.md).

The plan is intentionally prescriptive. An implementation agent should not redesign the API,
database model, concurrency strategy, broker topology, or delivery guarantees. It should implement
the current PRD exactly, run the stated tests, and stop.

## Fixed architecture

- Java 17, Spring Boot 4.1.2, Maven wrapper.
- Package-by-feature modular monolith under `ro.demo.ledger_api`.
- MySQL 8.4, Flyway, Spring Data JPA, and `BigDecimal` only for money.
- RabbitMQ 4.1 with durable exchanges, queues, persistent messages, publisher confirms, and a DLQ.
- REST under `/api/v1`; RFC 9457 Problem Details for errors.
- UUID identifiers represented as `CHAR(36)` in MySQL for PoC readability.
- UTC `Instant` timestamps persisted with microsecond precision.
- Explicit application services; controllers contain no business logic.
- Pessimistic account locking in ascending account-ID order.
- Persistent idempotency records for every mutating REST operation.
- Payment deduplication by `paymentId`, never by `eventId`.
- Transactional outbox for `transaction-recorded` announcements.
- At-least-once announcements: no committed transaction is intentionally missed, duplicates are
  possible, and global ordering is not guaranteed.
- Testcontainers with real MySQL and RabbitMQ for integration tests.

## Execution order and hard timebox

| Order | PRD | Functionality | Budget | Depends on |
|---:|---|---|---:|---|
| 1 | [PRD-001](PRD-001/PRD.md) | Foundation and API-first contracts | 10 min | - |
| 2 | [PRD-002](PRD-002/PRD.md) | Persistent REST idempotency | 9 min | PRD-001 |
| 3 | [PRD-003](PRD-003/PRD.md) | Open and read accounts | 7 min | PRD-002 |
| 4 | [PRD-004](PRD-004/PRD.md) | Record balanced transactions | 17 min | PRD-003 |
| 5 | [PRD-005](PRD-005/PRD.md) | Reverse transactions | 7 min | PRD-004 |
| 6 | [PRD-006](PRD-006/PRD.md) | Transaction and historical ledger queries | 8 min | PRD-004 |
| 7 | [PRD-007](PRD-007/PRD.md) | Consume settled-payment messages | 11 min | PRD-004 |
| 8 | [PRD-008](PRD-008/PRD.md) | Reliably announce recorded transactions | 11 min | PRD-004 |
| 9 | [PRD-009](PRD-009/PRD.md) | System proof, observability, Docker, and documentation | 15 min | PRD-005..008 |
| | | **Implementation total** | **95 min** | |
| | | **Integration contingency** | **10 min** | |
| | | **Hard limit** | **105 min** | |

Do not exceed a PRD's budget by polishing. If its acceptance tests pass, move on.

## Implementation status

- PRD-001: **DONE** (including the method-parameter validation review fix).
- PRD-002: **DONE** (MySQL-backed idempotency, exact-response replay, and concurrency/rollback tests).
- PRD-003: **DONE** (account API, settlement seeds, currency validation, and MySQL integration tests).
- PRD-004: **DONE** (atomic double-entry posting, deterministic locking, immutable ledger, and
  MySQL concurrency/integrity tests).
- PRD-005: **DONE** (single immutable reversal, idempotent retry, overdraft enforcement, and
  concurrent-reversal proof on MySQL).
- PRD-006: **DONE** (database-paged account entries and UTC historical-balance snapshots).
- PRD-007: **DONE** (RabbitMQ payment ingestion, payment-ID deduplication, bounded retries, DLQ,
  sample publisher, and real-infrastructure integration tests).
- PRD-008: **DONE** (transactional outbox, stable at-least-once events, publisher confirms,
  multi-instance locking, and RabbitMQ outage recovery).
- PRD-009: **DONE** (correlation observability, Docker delivery, verify-phase system proof, and
  complete operating/design documentation).

## Parallelism

After PRD-004 is complete, PRD-005, PRD-006, PRD-007, and PRD-008 touch separate feature
packages and can be implemented in parallel. PRD-009 must run last.

## Package layout

```text
ro.demo.ledger_api
├── account
├── idempotency
├── ledger
├── messaging
│   ├── payment
│   └── outbox
└── shared
    ├── api
    ├── domain
    └── observability
```

## Global implementation rules

1. Do not use `double` or `float` for money. Use `BigDecimal`, compare with `compareTo`, and never
   construct it from a floating-point value.
2. Supported currencies are exactly EUR (scale 2), USD (scale 2), and JPY (scale 0).
3. All database writes for one ledger operation are committed in one transaction.
4. Never update or delete a recorded ledger transaction or entry.
5. Lock every distinct affected account before checking an overdraft or writing a balance. Lock
   accounts in ascending textual UUID order.
6. Validation failures use stable machine-readable error codes and identify the bad field or entry.
7. Do not catch unexpected exceptions and turn them into success responses.
8. Do not add H2. Integration behavior is proved against MySQL and RabbitMQ.
9. Keep each PRD's tests with its feature. Do not postpone all testing to PRD-009.
10. Do not add dependencies unless the PRD explicitly requests one.

## Stable error codes

| HTTP | Code | Meaning |
|---:|---|---|
| 400 | `VALIDATION_FAILED` | Request shape or field validation failed |
| 400 | `UNSUPPORTED_CURRENCY` | Valid ISO code but not EUR, USD, or JPY |
| 400 | `INVALID_CURRENCY_CODE` | Not a three-letter ISO 4217 code |
| 400 | `INVALID_AMOUNT_SCALE` | Amount has too many decimal places |
| 400 | `UNBALANCED_TRANSACTION` | Debit and credit totals differ |
| 400 | `CURRENCY_MISMATCH` | Accounts or event currency differ |
| 400 | `REVERSAL_NOT_ALLOWED` | Target is a reversal |
| 404 | `ACCOUNT_NOT_FOUND` | Referenced account does not exist |
| 404 | `TRANSACTION_NOT_FOUND` | Referenced transaction does not exist |
| 409 | `IDEMPOTENCY_KEY_REUSED` | Same key used for a different request |
| 409 | `OVERDRAFT_LIMIT_EXCEEDED` | Resulting balance is below the allowed limit |
| 409 | `TRANSACTION_ALREADY_REVERSED` | Original already has a reversal |
| 409 | `PAYMENT_PAYLOAD_CONFLICT` | Same payment ID was redelivered with different data |

## Requirement coverage

| Requirement | Owning PRD |
|---|---|
| API-first contract | PRD-001 |
| Exact money and currency rules | PRD-001, PRD-004 |
| REST idempotency across instances | PRD-002 |
| Open/read account | PRD-003 |
| Record/read immutable transaction | PRD-004, PRD-006 |
| Atomicity, balance, overdraft, concurrency, no deadlock | PRD-004, PRD-009 |
| Reverse once; reversal cannot be reversed | PRD-005 |
| Account entries newest first with balance after | PRD-006 |
| Historical balance | PRD-006 |
| Payment ingestion, redelivery, poison-message handling | PRD-007 |
| Transaction announcements and crash behavior | PRD-008 |
| Correlation through logs and events | PRD-001, PRD-007, PRD-008, PRD-009 |
| Docker one-command run and real-infrastructure tests | PRD-009 |
| README, Improvements, sample requests, AI usage | PRD-009 |

## Final definition of done

- From a fresh clone, `./run-poc.sh` builds the Ledger API, starts MySQL, RabbitMQ, and the
  application, waits for readiness, runs the complete test suite, and exits with code 0 only when
  the stack is healthy and all tests pass. The only host prerequisites are Docker, Git, and JDK 17+.
- The application is healthy after the script completes.
- The full Maven test suite passes using real MySQL and RabbitMQ containers.
- OpenAPI and AsyncAPI contracts are committed and match implementation behavior.
- Every requirement in the coverage table has a passing test or explicit documentation proof.
