package ro.demo.ledger_api.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ro.demo.ledger_api.shared.api.ApiErrorCode;
import ro.demo.ledger_api.shared.api.ApiException;

@Service
public class ReversalService {
	private final LedgerTransactionRepository transactions;
	private final LedgerEntryRepository entries;
	private final LedgerPostingService posting;

	public ReversalService(LedgerTransactionRepository transactions,
			LedgerEntryRepository entries, LedgerPostingService posting) {
		this.transactions = transactions;
		this.entries = entries;
		this.posting = posting;
	}

	public TransactionResponse reverse(UUID originalId, ReverseTransactionRequest request) {
		LedgerTransactionEntity original = transactions.lockById(originalId.toString())
				.orElseThrow(() -> new ApiException(ApiErrorCode.TRANSACTION_NOT_FOUND,
						"Transaction " + originalId + " was not found"));
		if (original.reversedTransactionId() != null) {
			throw new ApiException(ApiErrorCode.REVERSAL_NOT_ALLOWED,
					"A reversal transaction cannot be reversed");
		}
		if (transactions.findReversalForUpdate(original.id()).isPresent()) {
			throw new ApiException(ApiErrorCode.TRANSACTION_ALREADY_REVERSED,
					"Transaction " + originalId + " has already been reversed");
		}

		List<LedgerEntryEntity> originalEntries =
				entries.findByTransactionIdOrderBySequenceNoAsc(original.id());
		List<EntryRequest> reversedEntries = originalEntries.stream()
				.map(entry -> new EntryRequest(
						UUID.fromString(entry.accountId()),
						entry.direction().opposite(),
						entry.amount()))
				.toList();
		String description = request.description() == null
				? "Reversal of " + originalId
				: request.description();
		return posting.record(
				new RecordTransactionRequest(description, reversedEntries),
				TransactionSource.REVERSAL,
				null,
				originalId);
	}
}
