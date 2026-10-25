package ro.demo.ledger_api.shared.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ro.demo.ledger_api.shared.api.ApiErrorCode;
import ro.demo.ledger_api.shared.api.ApiProblems;
import ro.demo.ledger_api.shared.api.ApiViolation;
import tools.jackson.databind.ObjectMapper;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {
	public static final String HEADER = "X-Correlation-Id";
	private final CorrelationContext context;
	private final ObjectMapper mapper;

	public CorrelationIdFilter(CorrelationContext context, ObjectMapper mapper) {
		this.context = context;
		this.mapper = mapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String supplied = request.getHeader(HEADER);
		String correlationId = supplied == null ? UUID.randomUUID().toString() : supplied;
		response.setHeader(HEADER, correlationId);
		if (!valid(correlationId)) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			mapper.writeValue(response.getOutputStream(), ApiProblems.create(
					org.springframework.http.HttpStatus.BAD_REQUEST,
					"Correlation ID must contain 1 to 128 printable characters",
					ApiErrorCode.VALIDATION_FAILED,
					List.of(new ApiViolation(HEADER, "INVALID_CORRELATION_ID",
							"Expected 1 to 128 printable characters"))));
			return;
		}
		try (CorrelationContext.Scope ignored = context.open(correlationId)) {
			filterChain.doFilter(request, response);
		}
	}

	private boolean valid(String value) {
		int length = value.codePointCount(0, value.length());
		return length >= 1 && length <= 128
				&& value.codePoints().allMatch(codePoint ->
						!Character.isISOControl(codePoint)
								&& (codePoint < Character.MIN_SURROGATE
										|| codePoint > Character.MAX_SURROGATE));
	}
}
