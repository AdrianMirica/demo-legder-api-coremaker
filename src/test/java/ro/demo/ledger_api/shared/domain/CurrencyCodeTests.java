package ro.demo.ledger_api.shared.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CurrencyCodeTests {
	@Test
	void supportedCurrenciesHaveTheirIsoScales() {
		assertEquals(2, CurrencyCode.EUR.scale());
		assertEquals(2, CurrencyCode.USD.scale());
		assertEquals(0, CurrencyCode.JPY.scale());
	}

	@Test
	void acceptsPositiveAmountsWithinCurrencyScale() {
		assertDoesNotThrow(() -> CurrencyCode.EUR.validateAmount(new BigDecimal("0.10")));
		assertDoesNotThrow(() -> CurrencyCode.JPY.validateAmount(new BigDecimal("100.00")));
	}

	@Test
	void rejectsNullZeroNegativeAndExcessScaleAmounts() {
		assertThrows(IllegalArgumentException.class, () -> CurrencyCode.EUR.validateAmount(null));
		assertThrows(IllegalArgumentException.class,
				() -> CurrencyCode.EUR.validateAmount(new BigDecimal("0")));
		assertThrows(IllegalArgumentException.class,
				() -> CurrencyCode.EUR.validateAmount(new BigDecimal("-1.00")));
		assertThrows(IllegalArgumentException.class,
				() -> CurrencyCode.EUR.validateAmount(new BigDecimal("1.001")));
		assertThrows(IllegalArgumentException.class,
				() -> CurrencyCode.JPY.validateAmount(new BigDecimal("100.01")));
	}
}
