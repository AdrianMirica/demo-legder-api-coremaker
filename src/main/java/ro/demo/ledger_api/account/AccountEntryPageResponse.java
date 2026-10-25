package ro.demo.ledger_api.account;

import java.util.List;

public record AccountEntryPageResponse(
		List<AccountEntryResponse> content,
		int page,
		int size,
		long totalElements,
		long totalPages) {
}
