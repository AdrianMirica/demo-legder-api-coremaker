# PRD-007 - Consume settled-payment messages

**Status:** DONE

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
The implementation uses `utf8mb4_0900_bin` on `payment_id`, matching REST idempotency's
case-sensitive, NO PAD identifier semantics.

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
   Payment IDs are limited to 128 Unicode code points, matching AsyncAPI and MySQL `VARCHAR(128)`,
   not 128 UTF-16 code units. The generated ledger description uses the same character-count
   convention against its 255-character limit.
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

`PaymentProcessingService` owns a `REQUIRES_NEW` transaction. The listener retry advice wraps the
entire call, so every retry gets a new transaction and persistence context. Reservation duplicates
use a current `FOR SHARE` read to avoid shared-to-exclusive lock upgrades. JPA flush precedes the
JDBC update linking the processed payment to the new transaction; acknowledgment follows commit.
Permanent failures roll back the reservation as well as ledger changes.

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

### Running the sample publisher

```bash
./scripts/send-payment-settled.sh payment-42 MERCHANT_ACCOUNT_UUID 125.50 EUR
```

By default this executes `rabbitmqadmin` v2 in Compose service `rabbitmq`. Before Compose is
implemented in PRD-009, it can target an existing management container:

```bash
RABBITMQ_CONTAINER=CONTAINER_ID ./scripts/send-payment-settled.sh payment-42 MERCHANT_ACCOUNT_UUID 125.50 EUR
```

The broker command reads `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS`, and optionally
`RABBITMQ_DEFAULT_VHOST` from the container environment (local image defaults are guest/guest and
`/`). The script requires no host-side broker tools or JSON package. It JSON-escapes payment IDs,
generates a UUID from OS entropy, prints the exact payload, and propagates publisher failures.
It verifies the `rabbitmqadmin` v2 routing result, returning nonzero when the exchange accepts a
message but no queue receives it, even if the CLI itself exits zero. CLI diagnostics are preserved;
an unrecognized success response fails explicitly instead of assuming successful routing.

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

`PaymentSettledIntegrationTests` uses the existing transitive Awaitility dependency. Tests send
actual persistent AMQP messages and inspect the original DLQ bodies and `x-death` trace headers.
Broker acknowledgment observation checks committed rows using a separate database connection.
Transient failures injected after JPA flush prove rollback before retry and the exact three-attempt
limit; permanent failures are verified to run once. The publisher script is exercised against
the real RabbitMQ management container, including a payment ID containing quotes and backslashes.
Regression tests cover supplementary Unicode payment IDs at and above the character limit, and a
sample publish to an existing exchange whose payment binding has been removed.

## Acceptance criteria

- [x] Deduplication key is `paymentId`, not `eventId`.
- [x] Poison messages are visible in the DLQ and do not block later messages.
- [x] Database commit occurs before broker acknowledgment.
- [x] A sample event can be sent without installing broker tools locally.
- [x] Integration tests use real RabbitMQ and MySQL.
- [x] `./mvnw test` passes.

## Stop condition

Do not implement transaction announcements; PRD-008 owns the outgoing exchange.
