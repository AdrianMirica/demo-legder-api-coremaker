# PRD-007 - Consume settled-payment messages

## Objective

Consume `payment-settled` messages from RabbitMQ, record each logical payment exactly once, and send
malformed or permanently invalid messages to a traceable dead-letter queue without blocking later
messages.

**Timebox:** 11 minutes  
**Prerequisite:** PRD-004  
**Contract:** `docs/api/asyncapi.yaml`

## Broker topology

Declare all resources as durable:

| Type | Name | Routing key |
|---|---|---|
| Topic exchange | `ledger.payments` | - |
| Queue | `ledger.payment-settled.q` | `payment.settled.v1` |
| Topic DLX | `ledger.payments.dlx` | - |
| Dead-letter queue | `ledger.payment-settled.dlq` | `payment.settled.v1` |

Configure the main queue with its DLX and dead-letter routing key. Messages are persistent.

## Database migration

Create `V5__create_processed_payments.sql`:

```sql
CREATE TABLE processed_payments (
    payment_id VARCHAR(128) NOT NULL,
    first_event_id CHAR(36) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    transaction_id CHAR(36) NULL,
    processed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (payment_id),
    CONSTRAINT fk_processed_payment_transaction
        FOREIGN KEY (transaction_id) REFERENCES ledger_transactions(id)
);
```

The row and ledger transaction must commit in the same database transaction.

## Implementation

Create under `ro.demo.ledger_api.messaging.payment`:

```text
PaymentMessagingConfiguration
PaymentSettledMessage
PaymentSettledListener
PaymentProcessingService
ProcessedPaymentRepository
PaymentPayloadHasher
```

### Listener and retry policy

- Use JSON conversion and the exact AsyncAPI payload.
- Use container `AUTO` acknowledgment: RabbitMQ acknowledges only after the listener returns and its
  database transaction commits.
- Configure at most 3 attempts for unexpected/transient failures with short exponential backoff.
- After attempts are exhausted, reject without requeue so RabbitMQ dead-letters the message.
- Conversion failures and permanent domain failures are rejected without requeue immediately.
- Log the error, `eventId`, `paymentId` when available, and final DLQ disposition.
- Do not catch and silently acknowledge failures.

### Deduplication algorithm

Inside one transaction:

1. Validate event ID, payment ID, merchant account UUID, positive amount, and currency.
2. Compute SHA-256 over payment ID, merchant account ID, amount normalized to the currency scale,
   and currency.
   Deliberately exclude `eventId`.
3. Use `INSERT IGNORE` to reserve `payment_id`.
4. If it already exists, read the committed row:
   - same hash: return successfully without recording another transaction;
   - different hash: throw `PAYMENT_PAYLOAD_CONFLICT` and dead-letter the message.
5. Resolve the merchant account and the seeded settlement account for the message currency.
6. Require the merchant account currency to equal the event currency.
7. Record through `LedgerPostingService`:
   - debit settlement account;
   - credit merchant account;
   - amount from the event;
   - source `PAYMENT`;
   - source reference `paymentId`;
   - description `Payment settled: {paymentId}`.
8. Update `processed_payments.transaction_id`.
9. Commit reservation, transaction, entries, and balances together.

Unknown account, wrong currency, malformed UUID, invalid amount scale, and conflicting redelivery are
permanent failures and go to the DLQ. A database or broker connectivity failure is transient.

## Sample publisher

Create `scripts/send-payment-settled.sh`:

- accepts payment ID, merchant account ID, amount, and currency arguments;
- generates a new event UUID;
- publishes through `rabbitmqadmin` inside the Docker Compose RabbitMQ container;
- sets content type to `application/json`, delivery mode 2, exchange `ledger.payments`, and routing
  key `payment.settled.v1`;
- prints the exact payload and event ID.

Exit nonzero with a usage message when arguments are missing.

## Tests

Use MySQL and RabbitMQ Testcontainers:

1. Valid event creates one balanced PAYMENT transaction.
2. Same event redelivered creates no duplicate.
3. Same payment under a new event ID creates no duplicate.
4. Same payment ID with changed amount reaches the DLQ.
5. Unknown merchant, wrong currency, excess decimals, and malformed JSON reach the DLQ.
6. Publish an invalid message followed by a valid one; valid one is processed.
7. Simultaneous duplicate deliveries create exactly one transaction.
8. Settlement and merchant balances remain exact.

Use Awaitility only if it already exists transitively; otherwise use a small bounded polling helper
in test code rather than adding a dependency.

## Acceptance criteria

- [ ] Deduplication key is `paymentId`, not `eventId`.
- [ ] Poison messages are visible in the DLQ and do not block later messages.
- [ ] Database commit occurs before broker acknowledgment.
- [ ] A sample event can be sent without installing broker tools locally.
- [ ] Integration tests use real RabbitMQ and MySQL.
- [ ] `./mvnw test` passes.

## Stop condition

Do not implement transaction announcements; PRD-008 owns the outgoing exchange.
