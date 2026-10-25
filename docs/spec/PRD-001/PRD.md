# PRD-001 - Foundation and API-first contracts

**Status:** DONE

## Objective

Create the smallest production-shaped foundation on which all other PRDs can build. Publish the
REST and event contracts before implementing business functionality.

**Timebox:** 10 minutes  
**Prerequisites:** generated Spring Initializr project at repository root  
**Out of scope:** account persistence, ledger writes, broker listeners, and outbox publishing

## Inputs

- [Global implementation plan](../README.md)
- [OpenAPI contract](openapi.yaml)
- [AsyncAPI contract](asyncapi.yaml)

## Required changes

### 1. Preserve and normalize the generated project

- Keep Java 17 and Spring Boot 4.1.2.
- Set Maven project name and description to meaningful values.
- Keep the dependencies already generated. Add no dependency in this PRD.
- Replace `mysql:latest` and `rabbitmq:latest` in test configuration with `mysql:8.4` and
  `rabbitmq:4.1-management`.

### 2. Commit the contracts as runtime documentation

Copy without redesign:

- `docs/spec/PRD-001/openapi.yaml` to `docs/api/openapi.yaml`
- `docs/spec/PRD-001/asyncapi.yaml` to `docs/api/asyncapi.yaml`

All later PRDs must keep these contracts synchronized when implementation reveals a correction.

### 3. Shared domain types

Create:

```text
src/main/java/ro/demo/ledger_api/shared/domain/CurrencyCode.java
src/main/java/ro/demo/ledger_api/shared/domain/EntryDirection.java
```

`CurrencyCode` is an enum with:

```text
EUR -> 2
USD -> 2
JPY -> 0
```

It exposes `int scale()` and `validateAmount(BigDecimal amount)`. Validation rejects null, zero,
negative values, and a scale larger than the currency scale. Trailing zeroes are allowed when their
normalized scale fits the currency (for example, `100.00` is valid JPY only if normalized to `100`).

`EntryDirection` contains `DEBIT` and `CREDIT` and exposes `opposite()`.

### 4. Error model

Create:

```text
src/main/java/ro/demo/ledger_api/shared/api/ApiException.java
src/main/java/ro/demo/ledger_api/shared/api/ApiErrorCode.java
src/main/java/ro/demo/ledger_api/shared/api/GlobalExceptionHandler.java
```

- `ApiException` carries HTTP status, stable error code, detail, and optional violations.
- `GlobalExceptionHandler` returns `application/problem+json` using Spring `ProblemDetail`.
- Add `errorCode` and, when applicable, `violations` properties.
- Map malformed JSON and Bean Validation failures to `VALIDATION_FAILED`.
- Unexpected exceptions remain 500 responses and are logged with the stack trace.

### 5. Configuration

Replace the minimal properties file with `application.yml`:

- datasource values from `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`;
- RabbitMQ values from `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, and
  `RABBITMQ_PASSWORD`;
- `spring.jpa.open-in-view=false`;
- `spring.jpa.hibernate.ddl-auto=validate`;
- Flyway enabled;
- UTC serialization;
- actuator health and info exposed;
- graceful shutdown enabled.

Create `src/test/resources/application-test.yml` only for deterministic test logging and timeouts.
Connection details continue to come from Testcontainers service connections.

## Tests

Create focused unit tests:

- every supported currency reports the correct scale;
- positive valid amounts pass;
- zero, negative, and excess-decimal amounts fail;
- `DEBIT.opposite()` is `CREDIT` and vice versa;
- a sample `ApiException` produces RFC 9457 JSON with `errorCode`.

## Acceptance criteria

- [x] Both contracts exist under `docs/api/`.
- [x] There is one authoritative currency enum; no feature duplicates currency-scale logic.
- [x] Problem responses have content type `application/problem+json`.
- [x] No business table or endpoint has been implemented.
- [x] `./mvnw test` passes.

## Stop condition

Stop as soon as the acceptance criteria pass. Do not begin PRD-002.
