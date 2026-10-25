package ro.demo.ledger_api.account;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AccountLedgerQueryServiceTests {
	@Test
	void calculatesLongTotalPagesWithoutFloatingPointOrIntNarrowing() {
		assertEquals(0, AccountLedgerQueryService.totalPages(0, 20));
		assertEquals(1, AccountLedgerQueryService.totalPages(1, 20));
		assertEquals(2, AccountLedgerQueryService.totalPages(21, 20));
		assertEquals(3_000_000_000L, AccountLedgerQueryService.totalPages(3_000_000_000L, 1));
		assertEquals(150_000_000L, AccountLedgerQueryService.totalPages(3_000_000_000L, 20));
	}
}
