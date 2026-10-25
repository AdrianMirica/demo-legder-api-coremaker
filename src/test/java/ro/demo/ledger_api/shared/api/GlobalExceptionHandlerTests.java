package ro.demo.ledger_api.shared.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTests {
	@Test
	void apiExceptionsUseRfc9457ProblemJsonWithErrorCode() throws Exception {
		mockMvc().perform(get("/test/api-error"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.type").value("about:blank"))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.detail").value("Invalid amount"))
				.andExpect(jsonPath("$.errorCode").value("INVALID_AMOUNT_SCALE"));
	}

	@Test
	void beanValidationFailuresUseProblemJsonWithFieldViolations() throws Exception {
		MockMvc mockMvc = mockMvc();

		mockMvc.perform(post("/test/validation")
						.contentType("application/json")
						.content("{\"name\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].field").value("name"));
	}

	@Test
	void malformedJsonUsesProblemJson() throws Exception {
		MockMvc mockMvc = mockMvc();

		mockMvc.perform(post("/test/malformed")
						.contentType("application/json")
						.content("{"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
	}

	@Test
	void methodParameterValidationFailuresUseProblemJsonWithParameterViolations() throws Exception {
		mockMvc().perform(get("/test/page?page=0"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].field").value("page"));
	}

	@Test
	void reversalUniqueConstraintMapsOnlyToAlreadyReversedConflict() throws Exception {
		mockMvc().perform(post("/test/reversal-constraint"))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.errorCode").value("TRANSACTION_ALREADY_REVERSED"));
	}

	private MockMvc mockMvc() {
		return MockMvcBuilders.standaloneSetup(new ErrorController())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@RestController
	static class ErrorController {
		@GetMapping("/test/api-error")
		void throwApiException() {
			throw new ApiException(ApiErrorCode.INVALID_AMOUNT_SCALE, "Invalid amount");
		}

		@PostMapping("/test/validation")
		void validate(@Valid @RequestBody ValidationRequest request) {
		}

		@PostMapping("/test/malformed")
		void parse(@RequestBody ValidationRequest request) {
		}

		@GetMapping("/test/page")
		void page(@Min(1) @RequestParam int page) {
		}

		@PostMapping("/test/reversal-constraint")
		void reversalConstraint() {
			throw new org.hibernate.exception.ConstraintViolationException(
					"Duplicate reversal",
					new SQLException("Duplicate entry", "23000", 1062),
					"insert into ledger_transactions",
					"uk_reversed_transaction");
		}
	}

	record ValidationRequest(@NotBlank String name) {
	}
}
