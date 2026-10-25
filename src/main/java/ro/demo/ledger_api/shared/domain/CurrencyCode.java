package ro.demo.ledger_api.shared.domain;

import java.math.BigDecimal;

public enum CurrencyCode {
	EUR(2),
	USD(2),
	JPY(0);

	private final int scale;

	CurrencyCode(int scale) {
		this.scale = scale;
	}

	public int scale() {
		return scale;
	}

	public void validateAmount(BigDecimal amount) {
		if (amount == null || amount.signum() <= 0
				|| amount.stripTrailingZeros().scale() > scale) {
			throw new IllegalArgumentException(
					"Amount must be positive and have no more than " + scale + " decimal places");
		}
	}
}
