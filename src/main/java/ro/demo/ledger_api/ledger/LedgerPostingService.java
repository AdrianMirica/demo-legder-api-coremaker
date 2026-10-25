package ro.demo.ledger_api.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.demo.ledger_api.account.AccountEntity;
import ro.demo.ledger_api.messaging.outbox.OutboxService;
import ro.demo.ledger_api.shared.api.ApiErrorCode;
import ro.demo.ledger_api.shared.api.ApiException;
import ro.demo.ledger_api.shared.api.ApiViolation;
import ro.demo.ledger_api.shared.domain.CurrencyCode;
import ro.demo.ledger_api.shared.domain.EntryDirection;

@Service
public class LedgerPostingService {
	private static final Logger logger = LoggerFactory.getLogger(LedgerPostingService.class);
	private static final BigDecimal MAX_STORED_AMOUNT = new BigDecimal("99999999999999999.99");

	private final AccountLockRepository accountLocks;
	private final LedgerTransactionRepository transactions;
	private final LedgerEntryRepository entries;
	private final OutboxService outbox;

	public LedgerPostingService(AccountLockRepository accountLocks,
			LedgerTransactionRepository transactions, LedgerEntryRepository entries,
			OutboxService outbox) {
		this.accountLocks = accountLocks;
		this.transactions = transactions;
		this.entries = entries;
		this.outbox = outbox;
	}

	public TransactionResponse recordApi(RecordTransactionRequest request) {
		return record(request, TransactionSource.API, null, null);
	}

	public TransactionResponse record(RecordTransactionRequest request, TransactionSource source,
			String sourceReference, UUID reversedTransactionId) {
		validateShape(request);
		List<String> sortedAccountIds = request.entries().stream()
				.map(entry -> entry.accountId().toString())
				.distinct()
				.sorted()
				.toList();
		List<AccountEntity> lockedAccounts = accountLocks.lockInOrder(sortedAccountIds);
		Map<String, AccountEntity> accountsById = new LinkedHashMap<>();
		lockedAccounts.forEach(account -> accountsById.put(account.id(), account));
		validateAccountsExist(request, sortedAccountIds, accountsById);

		CurrencyCode currency = lockedAccounts.get(0).currency();
		validateCurrencyAndAmounts(request, accountsById, currency);
		validateBalanced(request);

		Map<String, BigDecimal> deltas = aggregateDeltas(request, currency);
		validateFinalBalances(accountsById, deltas, currency);

		String transactionId = UUID.randomUUID().toString();
		Instant recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
		LedgerTransactionEntity transaction = new LedgerTransactionEntity(
				transactionId,
				request.description(),
				currency,
				Objects.requireNonNull(source, "source"),
				sourceReference,
				reversedTransactionId == null ? null : reversedTransactionId.toString(),
				recordedAt);
		transactions.save(transaction);

		Map<String, BigDecimal> runningBalances = new HashMap<>();
		accountsById.forEach((id, account) -> runningBalances.put(id, account.balance()));
		List<LedgerEntryEntity> persistedEntries = new ArrayList<>(request.entries().size());
		for (int index = 0; index < request.entries().size(); index++) {
			EntryRequest requestedEntry = request.entries().get(index);
			String accountId = requestedEntry.accountId().toString();
			BigDecimal normalizedAmount = requestedEntry.amount().setScale(currency.scale());
			BigDecimal signedAmount = requestedEntry.direction() == EntryDirection.CREDIT
					? normalizedAmount : normalizedAmount.negate();
			BigDecimal balanceAfter = runningBalances.get(accountId).add(signedAmount)
					.setScale(currency.scale());
			validateStoredRange(balanceAfter, "entries[" + index + "].balanceAfter");
			runningBalances.put(accountId, balanceAfter);
			persistedEntries.add(new LedgerEntryEntity(
					UUID.randomUUID().toString(),
					transactionId,
					index,
					accountId,
					requestedEntry.direction(),
					normalizedAmount,
					balanceAfter));
		}
		entries.saveAll(persistedEntries);
		deltas.forEach((accountId, delta) -> accountsById.get(accountId).applyDelta(delta));
		TransactionResponse response = TransactionMapper.toResponse(transaction, persistedEntries);
		transactions.flush();
		entries.flush();
		outbox.enqueue(response, currency);
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					logger.atInfo().addKeyValue("transactionId", response.id())
							.addKeyValue("source", response.source())
							.addKeyValue("currency", currency)
							.log("Ledger transaction committed");
				}
			});
		}
		return response;
	}

	@Transactional(readOnly = true)
	public TransactionResponse get(UUID transactionId) {
		LedgerTransactionEntity transaction = transactions.findById(transactionId.toString())
				.orElseThrow(() -> new ApiException(ApiErrorCode.TRANSACTION_NOT_FOUND,
						"Transaction " + transactionId + " was not found"));
		return TransactionMapper.toResponse(transaction,
				entries.findByTransactionIdOrderBySequenceNoAsc(transaction.id()));
	}

	private void validateShape(RecordTransactionRequest request) {
		if (request == null) {
			throw validation("request", "REQUIRED", "Request body is required");
		}
		if (request.description() == null || request.description().isBlank()
				|| request.description().codePointCount(0, request.description().length()) > 255) {
			throw validation("description", "SIZE", "Description must contain between 1 and 255 characters");
		}
		if (request.entries() == null || request.entries().size() < 2 || request.entries().size() > 100) {
			throw validation("entries", "SIZE", "A transaction must contain between 2 and 100 entries");
		}
		for (int index = 0; index < request.entries().size(); index++) {
			EntryRequest entry = request.entries().get(index);
			if (entry == null) {
				throw validation("entries[" + index + "]", "REQUIRED", "Entry is required");
			}
			if (entry.accountId() == null) {
				throw validation("entries[" + index + "].accountId", "REQUIRED", "Account ID is required");
			}
			if (entry.direction() == null) {
				throw validation("entries[" + index + "].direction", "REQUIRED", "Direction is required");
			}
			if (entry.amount() == null || entry.amount().signum() <= 0) {
				throw validation("entries[" + index + "].amount", "POSITIVE",
						"Amount must be greater than zero");
			}
			if (entry.amount().compareTo(MAX_STORED_AMOUNT) > 0) {
				throw validation("entries[" + index + "].amount", "DECIMAL_MAX",
						"Amount exceeds the maximum supported value");
			}
		}
	}

	private void validateAccountsExist(RecordTransactionRequest request, List<String> requestedIds,
			Map<String, AccountEntity> accountsById) {
		if (accountsById.size() == requestedIds.size()) {
			return;
		}
		for (int index = 0; index < request.entries().size(); index++) {
			if (!accountsById.containsKey(request.entries().get(index).accountId().toString())) {
				throw new ApiException(ApiErrorCode.ACCOUNT_NOT_FOUND,
						"Account " + request.entries().get(index).accountId() + " was not found",
						List.of(new ApiViolation("entries[" + index + "].accountId",
								"ACCOUNT_NOT_FOUND", "Account does not exist")));
			}
		}
		throw new ApiException(ApiErrorCode.ACCOUNT_NOT_FOUND, "One or more accounts do not exist");
	}

	private void validateCurrencyAndAmounts(RecordTransactionRequest request,
			Map<String, AccountEntity> accountsById, CurrencyCode currency) {
		for (int index = 0; index < request.entries().size(); index++) {
			EntryRequest entry = request.entries().get(index);
			AccountEntity account = accountsById.get(entry.accountId().toString());
			if (account.currency() != currency) {
				throw new ApiException(ApiErrorCode.CURRENCY_MISMATCH,
						"All transaction accounts must use the same currency",
						List.of(new ApiViolation("entries[" + index + "].accountId",
								"CURRENCY_MISMATCH", "Account currency differs from the transaction")));
			}
			if (entry.amount().stripTrailingZeros().scale() > currency.scale()) {
				throw new ApiException(ApiErrorCode.INVALID_AMOUNT_SCALE,
						"Amount has more decimal places than " + currency + " allows",
						List.of(new ApiViolation("entries[" + index + "].amount",
								"INVALID_AMOUNT_SCALE",
								"Maximum decimal places: " + currency.scale())));
			}
		}
	}

	private void validateBalanced(RecordTransactionRequest request) {
		BigDecimal debits = BigDecimal.ZERO;
		BigDecimal credits = BigDecimal.ZERO;
		for (EntryRequest entry : request.entries()) {
			if (entry.direction() == EntryDirection.DEBIT) {
				debits = debits.add(entry.amount());
			}
			else {
				credits = credits.add(entry.amount());
			}
		}
		if (debits.compareTo(credits) != 0) {
			throw new ApiException(ApiErrorCode.UNBALANCED_TRANSACTION,
					"Debit and credit totals must be equal",
					List.of(new ApiViolation("entries", "UNBALANCED_TRANSACTION",
							"Debit total " + debits.toPlainString() + " differs from credit total "
									+ credits.toPlainString())));
		}
	}

	private Map<String, BigDecimal> aggregateDeltas(RecordTransactionRequest request,
			CurrencyCode currency) {
		Map<String, BigDecimal> deltas = new HashMap<>();
		for (EntryRequest entry : request.entries()) {
			String accountId = entry.accountId().toString();
			BigDecimal amount = entry.amount().setScale(currency.scale());
			BigDecimal delta = entry.direction() == EntryDirection.CREDIT ? amount : amount.negate();
			deltas.merge(accountId, delta, BigDecimal::add);
		}
		return deltas;
	}

	private void validateFinalBalances(Map<String, AccountEntity> accounts,
			Map<String, BigDecimal> deltas, CurrencyCode currency) {
		for (Map.Entry<String, BigDecimal> delta : deltas.entrySet()) {
			AccountEntity account = accounts.get(delta.getKey());
			BigDecimal finalBalance = account.balance().add(delta.getValue()).setScale(currency.scale());
			validateStoredRange(finalBalance, "entries");
			if (account.overdraftLimit() != null
					&& finalBalance.compareTo(account.overdraftLimit().negate()) < 0) {
				throw new ApiException(ApiErrorCode.OVERDRAFT_LIMIT_EXCEEDED,
						"Transaction would exceed the overdraft limit for account " + account.id(),
						List.of(new ApiViolation("entries", "OVERDRAFT_LIMIT_EXCEEDED",
								"Resulting balance " + finalBalance.toPlainString()
										+ " is below the allowed limit")));
			}
		}
	}

	private void validateStoredRange(BigDecimal value, String field) {
		if (value.abs().compareTo(MAX_STORED_AMOUNT) > 0) {
			throw validation(field, "DECIMAL_RANGE", "Resulting monetary value exceeds storage range");
		}
	}

	private ApiException validation(String field, String code, String message) {
		return new ApiException(ApiErrorCode.VALIDATION_FAILED, message,
				List.of(new ApiViolation(field, code, message)));
	}
}
