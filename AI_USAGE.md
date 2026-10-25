# AI usage

AI tools were used for this PoC.

## What AI assisted with

- decomposing the requirements into implementation PRDs;
- drafting Spring Boot, MySQL, RabbitMQ, Docker, tests, and documentation;
- identifying edge cases in idempotency, concurrency, exact money, JSON validation, time zones,
  broker redelivery, and transactional outbox behavior;
- reviewing each completed PRD for concrete correctness issues.

The committed implementation instructions are under [`docs/spec/`](docs/spec/), and the normalized
source requirements are in [`docs/spec/requirements.md`](docs/spec/requirements.md).

## How output was checked

AI output was not accepted without validation. Changes were checked through:

- targeted tests after each implementation step;
- independent code-review passes after each PRD;
- MySQL 8.4 and RabbitMQ 4.1 Testcontainers integration tests;
- concurrency, deadlock, retry, rollback, redelivery, DLQ, publisher-confirm, and outage tests;
- Maven compile/test/verify;
- Docker Compose validation, container health checks, and the one-command runner;
- OpenAPI/AsyncAPI YAML parsing and synchronization checks.
- Human review after each PRD was implemented, test and reviewed with AI
