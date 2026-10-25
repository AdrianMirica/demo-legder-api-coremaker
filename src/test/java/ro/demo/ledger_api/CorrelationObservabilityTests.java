package ro.demo.ledger_api;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ro.demo.ledger_api.shared.observability.CorrelationContext;
import ro.demo.ledger_api.shared.observability.CorrelationIdFilter;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorrelationObservabilityTests {
	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private CorrelationContext correlations;

	@Test
	void suppliedCorrelationIdIsEchoedAndClearedEvenOnDomainFailure() throws Exception {
		String correlationId = "request-" + UUID.randomUUID();
		mockMvc.perform(get("/api/v1/accounts/{id}", UUID.randomUUID())
						.header(CorrelationIdFilter.HEADER, correlationId))
				.andExpect(status().isNotFound())
				.andExpect(header().string(CorrelationIdFilter.HEADER, correlationId));
		assertNull(correlations.currentId());
		assertNull(MDC.get(CorrelationContext.CORRELATION_ID));
	}

	@Test
	void absentCorrelationIdGeneratesAndEchoesUuid() throws Exception {
		String echoed = mockMvc.perform(get("/api/v1/accounts/{id}", UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andExpect(header().exists(CorrelationIdFilter.HEADER))
				.andReturn().getResponse().getHeader(CorrelationIdFilter.HEADER);
		assertTrue(echoed != null && UUID.fromString(echoed).toString().equals(echoed));
	}

	@Test
	void invalidCorrelationIdReturnsProblemWithoutReachingController() throws Exception {
		mockMvc.perform(get("/api/v1/accounts/{id}", UUID.randomUUID())
						.header(CorrelationIdFilter.HEADER, ""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].field")
						.value(CorrelationIdFilter.HEADER));
		mockMvc.perform(get("/api/v1/accounts/{id}", UUID.randomUUID())
						.header(CorrelationIdFilter.HEADER, "x".repeat(129)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
	}
}
