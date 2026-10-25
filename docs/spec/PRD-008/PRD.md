# PRD-008 - Reliably announce recorded transactions

## Objective

Publish every committed API, payment, and reversal transaction to other services without a
database/broker dual-write gap.

**Timebox:** 11 minutes  
**Prerequisite:** PRD-004  
**Contract:** `docs/api/asyncapi.yaml`

## Delivery guarantee

Implement a transactional outbox with these documented semantics:

- A committed ledger transaction is not intentionally missed.
- Delivery is **at least once**; consumers may see the same event more than once.
- Every retry uses the same stable `eventId`.
- Consumers must deduplicate by `eventId`.
- No global order is promised when several service instances publish concurrently.
- If the service crashes after broker confirmation but before marking the outbox row published, the
  event is published again.

Do not claim exactly-once delivery.

## Broker topology

Declare:

| Type | Name | Routing key |
|---|---|---|
| Durable topic exchange | `ledger.transactions` | `transaction.recorded.v1` |

Enable publisher confirms and returned-message handling. Publish persistent JSON messages.

## Database migration

Create `V6__create_outbox.sql`:

```sql
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
```

## Implementation

Create under `ro.demo.ledger_api.messaging.outbox`:

```text
OutboxEventEntity
OutboxEventRepository
TransactionRecordedEventFactory
OutboxService
OutboxPublisher
OutboxMessagingConfiguration
```

### Atomic event creation

Modify the single shared completion point in `LedgerPostingService`:

- create one outbox row after transaction and entries have their final IDs and snapshots;
- do this for sources `API`, `PAYMENT`, and `REVERSAL`;
- use outbox UUID as event `eventId`;
- serialize the exact AsyncAPI payload;
- carry the current correlation ID;
- for payment use incoming event ID as `causationId`;
- for REST use idempotency key as `causationId`;
- save the outbox row in the same database transaction as balances and ledger rows.

Do not publish directly from the request or listener transaction.

### Publisher

- Poll every 250 ms, batch size 100.
- In a short transaction select unpublished rows ordered by `created_at, id` using
  `FOR UPDATE SKIP LOCKED`; this permits several service instances.
- Publish to `ledger.transactions` with routing key `transaction.recorded.v1`.
- Wait for publisher confirm.
- Only after positive confirmation, set `published_at`.
- On publish failure, retain the row, increment `attempts`, store a bounded error message, and log at
  error level. The next poll retries it.
- A crash after publish and before marking is intentionally safe and can duplicate.

Do not hold a lock while sleeping between polls.

## Tests

Use MySQL and RabbitMQ Testcontainers:

1. API transaction commits one outbox row and eventually one matching event.
2. Payment transaction and reversal also produce events.
3. Event includes all entries, balances after, source, currency, correlation, and reversal link.
4. Rollback/refused transaction creates no outbox row or event.
5. Stop RabbitMQ, record a transaction, restore RabbitMQ, and verify later publication.
6. Simulate publication without marking and verify duplicate delivery has the same `eventId`.
7. Two publisher instances using `SKIP LOCKED` do not publish different event IDs for one row.

## Acceptance criteria

- [ ] There is no direct database-plus-RabbitMQ dual write.
- [ ] Every ledger write source creates an outbox event.
- [ ] Publisher confirms gate `published_at`.
- [ ] Failure and crash semantics are documented honestly and tested.
- [ ] `./mvnw test` passes.

## Stop condition

Do not add Docker delivery files or final documentation; PRD-009 owns them.
