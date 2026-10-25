package ro.demo.ledger_api.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.demo.ledger_api.shared.api.ApiErrorCode;
import ro.demo.ledger_api.shared.api.ApiException;
import ro.demo.ledger_api.shared.api.ApiViolation;
import ro.demo.ledger_api.shared.domain.CurrencyCode;

@Service
public class AccountService {
	private static final BigDecimal ZERO = BigDecimal.ZERO;
	private static final BigDecimal MAX_OVERDRAFT_LIMIT = new BigDecimal("99999999999999999.99");

	private final AccountRepository accounts;

	public AccountService(AccountRepository accounts) {
		this.accounts = accounts;
	}

	public AccountResponse open(OpenAccountRequest request) {
		CurrencyCode currency = parseCurrency(request.currency());
		BigDecimal overdraftLimit = request.overdraftLimit() == null ? ZERO : request.overdraftLimit();
		validateOverdraftLimit(overdraftLimit, currency);
		AccountEntity account = AccountEntity.open(UUID.randomUUID().toString(), currency,
				overdraftLimit.setScale(currency.scale()), Instant.now().truncatedTo(ChronoUnit.MICROS));
		return AccountMapper.toResponse(accounts.save(account));
	}

	@Transactional(readOnly = true)
	public AccountResponse get(UUID accountId) {
		return accounts.findById(accountId.toString())
				.map(AccountMapper::toResponse)
				.orElseThrow(() -> new ApiException(ApiErrorCode.ACCOUNT_NOT_FOUND,
						"Account " + accountId + " was not found"));
	}

	private CurrencyCode parseCurrency(String value) {
		if (value == null || !value.matches("[A-Z]{3}")) {
			throw new ApiException(ApiErrorCode.INVALID_CURRENCY_CODE,
					"Currency must be a valid uppercase ISO 4217 code",
					java.util.List.of(new ApiViolation("currency", "INVALID_CURRENCY_CODE",
							"Expected a three-letter uppercase ISO 4217 code")));
		}

		try {
			Currency.getInstance(value);
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(ApiErrorCode.INVALID_CURRENCY_CODE,
					"Currency " + value + " is not an ISO 4217 currency code",
					java.util.List.of(new ApiViolation("currency", "INVALID_CURRENCY_CODE",
							"Not a recognized ISO 4217 currency code")));
		}

		try {
			return CurrencyCode.valueOf(value);
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(ApiErrorCode.UNSUPPORTED_CURRENCY,
					"Currency " + value + " is valid but not supported");
		}
	}

	private void validateOverdraftLimit(BigDecimal amount, CurrencyCode currency) {
		if (amount.signum() < 0) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
					"Overdraft limit must not be negative",
					java.util.List.of(new ApiViolation("overdraftLimit", "DECIMAL_MIN",
							"Must be greater than or equal to zero")));
		}
		if (amount.stripTrailingZeros().scale() > currency.scale()) {
			throw new ApiException(ApiErrorCode.INVALID_AMOUNT_SCALE,
					"Overdraft limit has more decimal places than " + currency + " allows",
					java.util.List.of(new ApiViolation("overdraftLimit", "INVALID_AMOUNT_SCALE",
							"Maximum decimal places: " + currency.scale())));
		}
		if (amount.compareTo(MAX_OVERDRAFT_LIMIT) > 0) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
					"Overdraft limit exceeds the maximum supported value",
					java.util.List.of(new ApiViolation("overdraftLimit", "DECIMAL_MAX",
							"Must be less than or equal to " + MAX_OVERDRAFT_LIMIT.toPlainString())));
		}
	}
}
