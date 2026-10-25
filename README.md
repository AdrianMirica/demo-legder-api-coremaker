# Ledger API PoC

A Java 17 / Spring Boot 4 implementation of an exact, immutable double-entry ledger. It records
transactions through REST and RabbitMQ, protects balances under concurrent multi-instance access,
deduplicates retries and redeliveries, and reliably announces committed transactions through a
transactional outbox.

## Architecture

The service is a package-by-feature modular monolith:

- **accounts** hold one supported currency, an exact balance, and a finite overdraft limit;
- **ledger transactions and entries** are append-only and protected by MySQL triggers;
- **REST idempotency** stores request hashes and exact HTTP responses in MySQL;
- **payment ingestion** consumes durable RabbitMQ messages and deduplicates by `paymentId`;
- **transaction announcements** use a MySQL transactional outbox and publisher confirms.

MySQL is the single consistency boundary. Every posting locks all affected accounts in ascending UUID
order using one `SELECT ... FOR UPDATE`, validates the final aggregate balances, then commits the
transaction, entries, balances, idempotency/payment record, and outbox event atomically.

API contract: [`docs/api/openapi.yaml`](docs/api/openapi.yaml)

Event contract: [`docs/api/asyncapi.yaml`](docs/api/asyncapi.yaml)

Original requirements: [`docs/specs/requirements.md`](docs/specs/requirements.md)

## Prerequisites

- Docker Engine with Docker Compose v2;
- Git;
- JDK 17 or newer.

Local Maven, MySQL, RabbitMQ, `jq`, and broker command-line tools are not required.

## Quick start

From a fresh clone:

```bash
./run-poc.sh
```

The script:

1. validates Docker and Java;
2. builds and starts the healthy stack;
3. runs `./mvnw verify` against real Testcontainers infrastructure;
4. prints the dynamically assigned Ledger API URL.

The stack remains running after success. Use the printed URL:

```bash
export API_URL=http://localhost:PRINTED_PORT
```

See [Stack management](#stack-management) for health, logs, scaling, and shutdown commands.

## Manual startup without `run-poc.sh`

The following commands perform the same phases explicitly.

### 1. Check prerequisites and configuration

```bash
docker info
docker compose version
java -version
docker compose config --quiet
```

Java must be version 17 or newer.

### 2. Build the application image

```bash
docker compose build ledger-api
```

### 3. Start and wait for MySQL and RabbitMQ

```bash
docker compose up -d --wait mysql rabbitmq
docker compose ps
```

`rabbitmq-init` is a one-shot dependency that prepares the named volume with the correct Erlang
cookie ownership. It should finish with status `Exited (0)`.

### 4. Start and wait for the Ledger API

```bash
docker compose up -d --wait ledger-api
docker compose ps
```

The API uses a dynamically assigned host port. Discover and export it:

```bash
API_PORT=$(docker compose port ledger-api 8080 | head -n 1 | sed 's/.*://')
export API_URL="http://localhost:$API_PORT"
echo "$API_URL"
```

Verify readiness:

```bash
curl -i "$API_URL/actuator/health/readiness"
```

Expected status: `200`; expected body:

```json
{"status":"UP"}
```

### 5. Run all tests

```bash
./mvnw -B -ntp verify
```

## Stack management

### Health and ports

```bash
docker compose ps
docker compose port ledger-api 8080
curl -i "$API_URL/actuator/health/readiness"
```

### Logs

```bash
docker compose logs --no-color ledger-api
docker compose logs --no-color mysql rabbitmq
```

### Scale to two stateless application instances

```bash
docker compose up -d --wait --scale ledger-api=2
docker compose ps
```

Each replica receives a dynamic host port and shares MySQL and RabbitMQ.

### Stop

```bash
docker compose down
```

### Stop and delete local data

```bash
docker compose down -v
```

## Seeded settlement accounts

| Currency | Account ID |
|---|---|
| EUR | `00000000-0000-0000-0000-000000000978` |
| USD | `00000000-0000-0000-0000-000000000840` |
| JPY | `00000000-0000-0000-0000-000000000392` |

## API walkthrough and smoke test

These checks require host-side `curl`. Run them after exporting `API_URL` as shown above.
Every mutating request requires a client-generated `Idempotency-Key`. `X-Correlation-Id` is
optional; the service generates and echoes one when absent.

### 1. Open and read an EUR merchant account

```bash
curl -i -X POST "$API_URL/api/v1/accounts" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: manual-account-1' \
  -H 'X-Correlation-Id: manual-smoke-1' \
  -d '{"currency":"EUR"}'
```

Expected status: `201`. Copy the top-level response `id`:

```bash
export MERCHANT_ID=PASTE_ACCOUNT_ID
curl -i "$API_URL/api/v1/accounts/$MERCHANT_ID"
```

Expected status: `200`; initial balance: `0.00`.

Retry the same account request with the same key and body:

```bash
curl -i -X POST "$API_URL/api/v1/accounts" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: manual-account-1' \
  -d '{"currency":"EUR"}'
```

It returns the original account ID and response instead of creating another account.

### 2. Record and read a settlement transaction

```bash
curl -i -X POST "$API_URL/api/v1/transactions" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: manual-transaction-1' \
  -H 'X-Correlation-Id: manual-transaction-correlation-1' \
  -d "{
    \"description\":\"Manual Docker smoke settlement\",
    \"entries\":[
      {\"accountId\":\"00000000-0000-0000-0000-000000000978\",\"direction\":\"DEBIT\",\"amount\":10.50},
      {\"accountId\":\"$MERCHANT_ID\",\"direction\":\"CREDIT\",\"amount\":10.50}
    ]
  }"
```

Expected status: `201`. Copy the **top-level transaction** `id`, not an entry ID:

```bash
export TRANSACTION_ID=PASTE_TOP_LEVEL_TRANSACTION_ID
curl -i "$API_URL/api/v1/transactions/$TRANSACTION_ID"
```

Expected status: `200`; the merchant entry has `balanceAfter: 10.50`.

### 3. Read paged entries and historical balance

```bash
curl -i "$API_URL/api/v1/accounts/$MERCHANT_ID/entries?page=0&size=10"

sleep 1
AT=$(date -u +'%Y-%m-%dT%H:%M:%SZ')
curl -i --get "$API_URL/api/v1/accounts/$MERCHANT_ID/balance" \
  --data-urlencode "at=$AT"
```

Expected historical balance: `10.50`. The short wait ensures the whole-second example instant is
after the transaction's microsecond timestamp.

### 4. Reverse the transaction

```bash
curl -i -X POST "$API_URL/api/v1/transactions/$TRANSACTION_ID/reversal" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: manual-reversal-1' \
  -d '{"description":"Reverse Docker smoke settlement"}'

curl -i "$API_URL/api/v1/accounts/$MERCHANT_ID"
```

Expected reversal status: `201`; expected final merchant balance: `0.00`.

### 5. Prove an unbalanced transaction is refused

```bash
curl -i -X POST "$API_URL/api/v1/transactions" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: manual-refused-1' \
  -d "{
    \"description\":\"Must be refused\",
    \"entries\":[
      {\"accountId\":\"00000000-0000-0000-0000-000000000978\",\"direction\":\"DEBIT\",\"amount\":2.00},
      {\"accountId\":\"$MERCHANT_ID\",\"direction\":\"CREDIT\",\"amount\":1.00}
    ]
  }"
```

Expected status: `400`; expected `errorCode`: `UNBALANCED_TRANSACTION`. Reading the merchant again
must still show `0.00`.

## Send a sample settled payment

```bash
./scripts/send-payment-settled.sh payment-42 "$MERCHANT_ID" 125.50 EUR
```

The script prints the generated `eventId` and exact JSON, then runs `rabbitmqadmin` inside the
Compose RabbitMQ container. It exits nonzero when the message is not routed.

RabbitMQ management is available at <http://localhost:15672> using the local Compose credentials
`ledger` / `rabbit-local-password`. Permanently invalid payment events are visible in
`ledger.payment-settled.dlq`, including RabbitMQ `x-death` trace headers.

## Exact money and currencies

- Amounts use `BigDecimal` and MySQL `DECIMAL`; `double`/`float` are never used.
- EUR and USD allow 2 decimal places; JPY allows 0.
- All amounts are positive and all entries in one transaction use one currency.
- Debit totals must exactly equal credit totals.
- The sum of all account balances in each currency remains exactly zero.

## Idempotency and redelivery

REST keys are scoped by operation. An identical retry returns the byte-equivalent original status
and body from MySQL, including after another instance handles it. Reusing a key for a different
validated request returns `409 IDEMPOTENCY_KEY_REUSED`.

Payments are deduplicated by the case-sensitive logical `paymentId`, deliberately not `eventId`.
Redelivery under a new event ID records nothing twice. Reusing a payment ID with different merchant,
amount, or currency is dead-lettered as a conflict.

## Announcement guarantees

Every API transaction, payment, and reversal inserts a `transaction-recorded` outbox event in the
same MySQL transaction. A consumer can rely on:

- no intentional miss after the ledger transaction commits;
- **at-least-once**, not exactly-once delivery;
- a stable `eventId` across retries; consumers must deduplicate by it;
- no global ordering across publisher instances;
- persistent JSON messages and positive RabbitMQ publisher confirms;
- broker outage retaining `published_at = NULL` for later retry;
- a possible duplicate if the service crashes after broker confirmation but before marking the row.

## Correlation and logs

REST accepts and echoes `X-Correlation-Id` (1–128 printable characters). Payment processing uses the
AMQP correlation ID or falls back to `eventId`. MDC/log fields include:

- `correlationId`;
- `eventId` and `paymentId` for incoming payments;
- ledger `transactionId`, source, and currency;
- outbox event ID, aggregate ID, and routing key.

Outgoing announcement bodies and AMQP headers carry correlation and causation IDs. Credentials and
failed request bodies are not logged.

## Tests

```bash
./mvnw test
./mvnw verify
```

`test` runs focused unit/integration tests. `verify` additionally runs `LedgerGuaranteesIT`, which
proves atomic refusal, per-currency integrity, 20 parallel competing postings, opposite transfers,
cross-context REST idempotency, payment redelivery, outbox recovery, database immutability, paging,
balance snapshots, and historical balances against real MySQL and RabbitMQ.

## Design limitations

This is a focused PoC. It intentionally uses database balances plus immutable entry snapshots rather
than deriving current balances on every read. The database triggers, constraints, deterministic
locks, and tests protect that optimization.

## Improvements

Before production:

- add authentication, authorization, tenant isolation, and request quotas;
- move credentials to a secrets manager and enable TLS/mTLS;
- automate OpenAPI/AsyncAPI compatibility and generated-client checks;
- partition and expire published outbox/idempotency rows;
- add business metrics, alerts, a tracing backend, dashboards, and SLOs;
- define backup, point-in-time restore, and disaster-recovery procedures;
- run sustained load, soak, failure-injection, and chaos tests;
- define a multi-region consistency and failover strategy;
- replace local Compose credentials and tune database/broker pools from measured load.

See [`AI_USAGE.md`](AI_USAGE.md) for AI-assistance disclosure and validation.
