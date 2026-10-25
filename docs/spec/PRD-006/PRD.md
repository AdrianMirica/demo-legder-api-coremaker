# PRD-006 - Transaction and historical ledger queries

## Objective

Complete the read side: retrieve transactions, page an account's entries newest first with the
balance after each entry, and read an account balance at a past instant.

**Timebox:** 8 minutes  
**Prerequisite:** PRD-004  
**Endpoints:**

- `GET /api/v1/transactions/{transactionId}`
- `GET /api/v1/accounts/{accountId}/entries`
- `GET /api/v1/accounts/{accountId}/balance?at={instant}`

## Implementation

Create under the existing `account` and `ledger` feature packages:

```text
AccountLedgerQueryService
AccountEntryResponse
AccountEntryPageResponse
HistoricalBalanceResponse
```

### Transaction query

- Reuse `TransactionMapper`.
- Fetch entries in `sequence_no ASC`.
- Avoid an N+1 query; use one transaction query and one ordered entry query or an entity graph.
- Return `404 TRANSACTION_NOT_FOUND` when absent.

### Account entries

- Verify the account exists before querying entries.
- Accept zero-based `page`, default 0.
- Accept `size`, default 20, minimum 1, maximum 100.
- Return newest first using this deterministic order:
  1. `ledger_transactions.recorded_at DESC`;
  2. `ledger_transactions.ledger_sequence DESC`;
  3. `ledger_entries.sequence_no DESC`.
- Each item contains entry fields plus transaction ID, description, and recorded timestamp.
- `balanceAfter` comes from the immutable entry snapshot; never calculate it from current balance.
- Return the page envelope specified in OpenAPI.

### Historical balance

- Require an ISO-8601 UTC-capable `Instant` query parameter named `at`.
- Reject a future instant with `VALIDATION_FAILED`; current balance is already available on account.
- Verify the account exists.
- Find the newest entry at or before `at` using the same deterministic order as above.
- Return that entry's `balance_after`; return exact zero in account currency when no entry exists.
- Echo the requested instant as `asOf`.

### Repository queries

Use explicit JPQL/native projections. Do not load all entries and page in memory. Reuse the indexes
created in PRD-004; this PRD adds no migration.

## Tests

1. Transaction read returns entries in request sequence.
2. Unknown transaction and account return their stable 404 codes.
3. Create more entries than page size and verify page metadata and descending order.
4. Every paged item contains the correct stored `balanceAfter`.
5. Balance before the account's first entry is zero.
6. Balance between two transactions equals the first transaction's account balance.
7. Balance after the latest transaction equals current account balance.
8. JPY historical balances have no fractional part.
9. A future `at` is rejected.

## Acceptance criteria

- [ ] Queries are paged and database-backed.
- [ ] Ordering is deterministic even for equal timestamps.
- [ ] Historical balances use immutable entry snapshots.
- [ ] All three read endpoints match OpenAPI.
- [ ] `./mvnw test` passes.

## Stop condition

Do not implement messaging or change ledger write rules.
