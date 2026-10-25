# PRD-005 - Reverse transactions

**Status:** DONE

## Objective

Record exactly one immutable reversal for an original transaction, using the same ledger validation,
locking, overdraft, idempotency, and atomicity rules as any other transaction.

**Timebox:** 7 minutes  
**Prerequisite:** PRD-004  
**Endpoint:** `POST /api/v1/transactions/{transactionId}/reversal`

## Implementation

Create or extend:

```text
src/main/java/ro/demo/ledger_api/ledger/ReversalService.java
src/main/java/ro/demo/ledger_api/ledger/ReverseTransactionRequest.java
src/main/java/ro/demo/ledger_api/ledger/TransactionController.java
src/main/java/ro/demo/ledger_api/ledger/LedgerTransactionRepository.java
```

### Algorithm

Run through `IdempotencyService` using operation
`REVERSE_TRANSACTION:{transactionId}`:

1. Lock the target transaction row with `SELECT ... FOR UPDATE`.
2. Return `TRANSACTION_NOT_FOUND` if absent.
3. If `reversed_transaction_id` on the target is non-null, the target is itself a reversal; return
   `400 REVERSAL_NOT_ALLOWED`.
4. Query for a transaction whose `reversed_transaction_id` equals the target ID. If found, return
   `409 TRANSACTION_ALREADY_REVERSED`.
5. Read original entries in sequence order.
6. Build a new internal ledger command with the same accounts and amounts and every direction
   flipped.
7. Use request description when supplied; otherwise use `Reversal of {originalId}`.
8. Record through the existing `LedgerPostingService`; do not duplicate posting logic.
9. Set source `REVERSAL` and `reversed_transaction_id = originalId` on the new transaction.
10. Return `201` and the new transaction.

The unique constraint on `reversed_transaction_id` is the final race-condition defense. Convert its
specific duplicate-key violation to `TRANSACTION_ALREADY_REVERSED`; do not broadly convert database
errors.

The global exception mapping recognizes only Hibernate constraint violations whose constraint name
is exactly `uk_reversed_transaction`. Other integrity failures remain unexpected server errors and
are logged rather than being mislabeled as reversal conflicts.

The reversal can fail with `OVERDRAFT_LIMIT_EXCEEDED`. In that case, record nothing.

An absent request body and `{}` both select the default description and hash identically. Explicit
JSON `null` is not an absent body and is rejected with `400 VALIDATION_FAILED`. Supplied
descriptions must contain at least one non-whitespace character and be at most 255 characters,
matching the OpenAPI pattern.

## Tests

1. Reverse the payout example and verify flipped entries and restored balances.
2. Original transaction remains byte-for-byte unchanged.
3. Second reversal with a different idempotency key returns
   `TRANSACTION_ALREADY_REVERSED`.
4. Retry with the same key returns the first reversal.
5. Attempt to reverse a reversal returns `REVERSAL_NOT_ALLOWED`.
6. Reversal that breaches an overdraft is refused and leaves no rows or balance changes.
7. Two concurrent reversal requests with different keys create one reversal only.
8. Reversal appears in normal transaction retrieval with source and link fields.

## Acceptance criteria

- [x] Exactly one reversal can exist for an original transaction.
- [x] Reversal reuses the normal posting path.
- [x] Original transaction and entries are never modified.
- [x] Concurrent reversal behavior is proved against MySQL.
- [x] `./mvnw test` passes.

## Implementation entry point and verification

`TransactionController` normalizes an absent reversal body and `{}` to the same request before
idempotency hashing, scopes keys as `REVERSE_TRANSACTION:{transactionId}`, and returns the reversal
resource location. `ReversalService` locks the original transaction, performs a current locked read
for an existing reversal, flips immutable entries in their original order, and invokes
`LedgerPostingService.record` with source `REVERSAL` and the original transaction link.

`ReversalApiTests` proves flipped entries and restored balances, byte-identical original reads,
same-key replay, second-reversal rejection, reversal-of-reversal rejection, default descriptions,
atomic overdraft refusal, strict request JSON, and two simultaneous different-key requests creating
exactly one reversal. `GlobalExceptionHandlerTests` proves that only the named reversal uniqueness
constraint is translated to `TRANSACTION_ALREADY_REVERSED`.

## Stop condition

Do not implement historical queries or messaging.
