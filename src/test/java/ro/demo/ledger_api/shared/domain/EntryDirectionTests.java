package ro.demo.ledger_api.shared.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EntryDirectionTests {
	@Test
	void oppositeReturnsTheOtherDirection() {
		assertEquals(EntryDirection.CREDIT, EntryDirection.DEBIT.opposite());
		assertEquals(EntryDirection.DEBIT, EntryDirection.CREDIT.opposite());
	}
}
