package ro.demo.ledger_api.shared.domain;

public enum EntryDirection {
	DEBIT,
	CREDIT;

	public EntryDirection opposite() {
		return this == DEBIT ? CREDIT : DEBIT;
	}
}
