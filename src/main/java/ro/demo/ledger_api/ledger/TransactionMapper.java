package ro.demo.ledger_api.ledger;

import java.util.List;
import java.util.UUID;

public final class TransactionMapper {
	private TransactionMapper() {
	}

	public static TransactionResponse toResponse(LedgerTransactionEntity transaction,
			List<LedgerEntryEntity> entries) {
		return new TransactionResponse(
				UUID.fromString(transaction.id()),
				transaction.description(),
				transaction.source(),
				transaction.recordedAt(),
				transaction.reversedTransactionId() == null
						? null : UUID.fromString(transaction.reversedTransactionId()),
				entries.stream().map(TransactionMapper::toResponse).toList());
	}

	private static EntryResponse toResponse(LedgerEntryEntity entry) {
		return new EntryResponse(
				UUID.fromString(entry.id()),
				entry.sequenceNo(),
				UUID.fromString(entry.accountId()),
				entry.direction(),
				entry.amount(),
				entry.balanceAfter());
	}
}
