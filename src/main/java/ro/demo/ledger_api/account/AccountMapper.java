package ro.demo.ledger_api.account;

import java.util.UUID;

public final class AccountMapper {
	private AccountMapper() {
	}

	public static AccountResponse toResponse(AccountEntity account) {
		return new AccountResponse(
				UUID.fromString(account.id()),
				account.currency(),
				account.overdraftLimit(),
				account.balance(),
				account.settlementAccount(),
				account.createdAt());
	}
}
