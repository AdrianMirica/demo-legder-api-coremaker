# PRD-009 - System proof, observability, Docker, and documentation

**Status:** DONE

## Objective

Turn the implemented features into a reproducible PoC: one command builds, starts, and tests it;
system tests prove all guarantees; logs correlate REST and payment flows; documentation contains
every required decision and sample.

**Timebox:** 15 minutes  
**Prerequisites:** PRD-005, PRD-006, PRD-007, PRD-008

## Observability

Create under `ro.demo.ledger_api.shared.observability`:

```text
CorrelationIdFilter
CorrelationContext
MessagingCorrelationInterceptor
```

### REST

- Accept optional `X-Correlation-Id`; validate 1..128 printable characters.
- Generate a UUID when absent.
- Put it in SLF4J MDC as `correlationId`.
- Echo it in the response header.
- Clear MDC in `finally`.

### Messaging

- For incoming payments, use AMQP `correlationId` when present, otherwise `eventId`.
- Put `correlationId`, `eventId`, and `paymentId` in MDC while processing.
- Outgoing announcements contain `correlationId` and `causationId` in body and AMQP headers.
- Log structured key/value facts at intake, ledger commit, outbox publish, retry, and DLQ.
- Never log credentials or entire exception-bearing request bodies.

## Docker delivery

Create:

```text
Dockerfile
compose.yaml
.dockerignore
run-poc.sh
```

### Dockerfile

- Multi-stage build.
- Build stage uses a pinned Maven JDK 17 image and `./mvnw`.
- Runtime uses a pinned Java 17 JRE image and a non-root user.
- Expose 8080.
- Health check uses `/actuator/health/readiness`.

### Compose services

1. `mysql`
   - image `mysql:8.4`;
   - database and non-root app credentials from compose environment;
   - health check with `mysqladmin ping`;
   - named volume.
2. `rabbitmq`
   - image `rabbitmq:4.1-management`;
   - non-default local credentials;
   - health check with `rabbitmq-diagnostics -q ping`;
   - management port available for PoC inspection.
3. `ledger-api`
   - build from the Dockerfile;
   - starts only after healthy MySQL and RabbitMQ;
   - two replicas must be possible with `docker compose up --scale ledger-api=2`;
   - no local state required.

Use environment variables; do not commit real credentials.

### One command

`./run-poc.sh` must:

1. use `set -euo pipefail`;
2. verify Docker is reachable;
3. run `docker compose up --build -d --wait`;
4. run `./mvnw verify` (tests use Testcontainers);
5. print health URL, RabbitMQ management URL, and sample script usage;
6. on failure, print `docker compose logs --no-color` and exit nonzero;
7. leave the healthy stack running after success.

The only host requirements are Docker, Git, and a JDK. Do not require local Maven, MySQL, RabbitMQ,
`jq`, or language package managers.

## System proof tests

Create a focused `LedgerGuaranteesIT` and run `*IT` in Maven `verify`. It must prove:

1. **Atomic refusal:** every invalid transaction leaves transaction count, entry count, outbox
   count, and all balances unchanged.
2. **Integrity:** after mixed API, reversal, and payment operations, balances per currency sum to
   exact zero.
3. **Concurrency:** at least 20 parallel competing postings never exceed overdrafts or lose updates.
4. **Opposite transfers:** repeated parallel `A -> B` and `B -> A` complete under a timeout without
   deadlock.
5. **REST idempotency:** concurrent retries against two application contexts produce one effect and
   byte-equivalent responses.
6. **Payment redelivery:** same payment with multiple event IDs records once.
7. **Outbox recovery:** broker unavailability does not roll back a committed ledger transaction and
   the event is delivered after recovery.
8. **Immutability:** direct update/delete attempts fail.
9. **Nice-to-have reads:** pagination, balance-after, and historical balance are correct.

Keep tests deterministic: bounded waits, fixed seeds, no arbitrary long sleeps, and clear failure
messages.

## Documentation

Replace the root README with:

1. purpose and architecture;
2. prerequisite versions;
3. `./run-poc.sh` quick start and shutdown command;
4. API examples for account, transaction, reversal, reads, paging, and historical balance;
5. sample `payment-settled` command;
6. links to OpenAPI and AsyncAPI;
7. database/concurrency/locking decisions;
8. exact-money and currency decisions;
9. idempotency behavior and key reuse;
10. payment deduplication and DLQ inspection;
11. outbox guarantees:
    - no intentional miss after database commit;
    - duplicates possible;
    - stable event ID;
    - no global ordering;
    - crash-after-publish behavior;
12. correlation and log fields;
13. how to run tests;
14. an **Improvements** section listing production work intentionally omitted:
    - authentication/authorization;
    - secrets manager and TLS;
    - schema/contract compatibility automation;
    - outbox retention/partitioning;
    - metrics, alerts, tracing backend, and SLOs;
    - backup, restore, disaster recovery;
    - load/soak/chaos tests;
    - multi-region strategy.

Create `AI_USAGE.md` with:

- whether AI was used;
- which tasks it helped with;
- how every generated change was reviewed and tested;
- references to committed instruction/PRD files;
- no claim that AI output was accepted without validation.

## Acceptance criteria

- [x] All functional, non-functional, and nice-to-have requirements are implemented.
- [x] `./run-poc.sh` succeeds from a clean checkout.
- [x] `./mvnw verify` passes against real MySQL and RabbitMQ.
- [x] Stack remains healthy after tests.
- [x] README documents decisions, samples, guarantees, limitations, and Improvements.
- [x] OpenAPI and AsyncAPI match runtime behavior.
- [x] No secret, generated archive, build output, or database data is committed.

## Verification evidence

- `./mvnw clean verify`: 103 Surefire tests and 4 `LedgerGuaranteesIT` Failsafe tests passed.
- `./run-poc.sh`: Docker image build, Compose health, and the complete Maven verification passed;
  the script left the stack healthy and printed the mapped API URL.
- `docker compose up --scale ledger-api=2`: two stateless application replicas reached healthy
  state on separate dynamic host ports.
- A manual smoke flow opened an EUR account through REST, published `payment-settled` with
  `scripts/send-payment-settled.sh`, and read an exact `1.50` balance through REST.
- RabbitMQ management and MySQL remained healthy after all tests. The RabbitMQ named volume uses a
  one-shot initializer to create the Erlang cookie with the official image user ownership on Docker
  Desktop implementations that otherwise copy it as root.

## Stop condition

The PoC is complete when the script succeeds and the final acceptance criteria are checked. Do not
add unrelated production infrastructure.
