package ro.demo.ledger_api.shared.api;

import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.DatabindException;

@RestControllerAdvice
public class GlobalExceptionHandler {
	private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ProblemDetail handleApiException(ApiException exception) {
		return ApiProblems.from(exception);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail handleMalformedRequest(HttpMessageNotReadableException exception) {
		if (exception.getCause() instanceof DatabindException mappingException
				&& !mappingException.getPath().isEmpty()) {
			String field = jsonPath(mappingException);
			if (!field.isEmpty()) {
				return problem(HttpStatus.BAD_REQUEST, "Request body does not match the JSON contract",
						ApiErrorCode.VALIDATION_FAILED,
						List.of(new ApiViolation(field, "INVALID_VALUE", "Invalid JSON property or value")));
			}
		}
		return problem(HttpStatus.BAD_REQUEST, "Malformed JSON request",
				ApiErrorCode.VALIDATION_FAILED, List.of());
	}

	private String jsonPath(DatabindException exception) {
		StringBuilder path = new StringBuilder();
		for (tools.jackson.core.JacksonException.Reference reference : exception.getPath()) {
			if (reference.getPropertyName() != null) {
				if (!path.isEmpty()) {
					path.append('.');
				}
				path.append(reference.getPropertyName());
			}
			else if (reference.getIndex() >= 0) {
				path.append('[').append(reference.getIndex()).append(']');
			}
		}
		return path.toString();
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ProblemDetail handleArgumentTypeMismatch(MethodArgumentTypeMismatchException exception) {
		return problem(HttpStatus.BAD_REQUEST, "Request parameter has an invalid format",
				ApiErrorCode.VALIDATION_FAILED,
				List.of(new ApiViolation(exception.getName(), "TYPE_MISMATCH", "Invalid parameter format")));
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	ProblemDetail handleMissingHeader(MissingRequestHeaderException exception) {
		List<ApiViolation> violations = List.of(new ApiViolation(exception.getHeaderName(),
				"REQUIRED", "Required request header is missing"));
		return problem(HttpStatus.BAD_REQUEST, "Required request header is missing",
				ApiErrorCode.VALIDATION_FAILED, violations);
	}

	@ExceptionHandler(BindException.class)
	ProblemDetail handleBindingFailure(BindException exception) {
		List<ApiViolation> violations = exception.getBindingResult().getFieldErrors().stream()
				.map(this::toViolation)
				.toList();
		return problem(HttpStatus.BAD_REQUEST, "Request validation failed",
				ApiErrorCode.VALIDATION_FAILED, violations);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ProblemDetail handleConstraintViolation(ConstraintViolationException exception) {
		List<ApiViolation> violations = exception.getConstraintViolations().stream()
				.map(violation -> new ApiViolation(
						violation.getPropertyPath().toString(),
						violation.getConstraintDescriptor().getAnnotation().annotationType()
								.getSimpleName(),
						violation.getMessage()))
				.toList();
		return problem(HttpStatus.BAD_REQUEST, "Request validation failed",
				ApiErrorCode.VALIDATION_FAILED, violations);
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	ProblemDetail handleMethodValidation(HandlerMethodValidationException exception) {
		if (exception.isForReturnValue()) {
			logger.error("Controller return value validation failed", exception);
			return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred",
					ApiErrorCode.INTERNAL_ERROR, List.of());
		}

		List<ApiViolation> violations = new ArrayList<>();
		for (ParameterValidationResult result : exception.getParameterValidationResults()) {
			String parameterName = result.getMethodParameter().getParameterName();
			String field = parameterName == null
					? "parameter[" + result.getMethodParameter().getParameterIndex() + "]"
					: parameterName;
			result.getResolvableErrors().stream()
					.map(error -> toViolation(field, error))
					.forEach(violations::add);
		}
		exception.getCrossParameterValidationResults().stream()
				.map(error -> toViolation("parameters", error))
				.forEach(violations::add);
		return problem(HttpStatus.BAD_REQUEST, "Request validation failed",
				ApiErrorCode.VALIDATION_FAILED, violations);
	}

	@ExceptionHandler(Exception.class)
	ProblemDetail handleUnexpectedException(Exception exception) {
		if (hasConstraint(exception, "uk_reversed_transaction")) {
			return problem(HttpStatus.CONFLICT, "Transaction has already been reversed",
					ApiErrorCode.TRANSACTION_ALREADY_REVERSED, List.of());
		}
		logger.error("Unexpected request failure", exception);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred",
				ApiErrorCode.INTERNAL_ERROR, List.of());
	}

	private boolean hasConstraint(Throwable exception, String expectedConstraint) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
					&& expectedConstraint.equalsIgnoreCase(violation.getConstraintName())) {
				return true;
			}
		}
		return false;
	}

	private ApiViolation toViolation(FieldError error) {
		return new ApiViolation(error.getField(),
				error.getCode() == null ? "INVALID_VALUE" : error.getCode(),
				error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage());
	}

	private ApiViolation toViolation(String field, MessageSourceResolvable error) {
		String[] codes = error.getCodes();
		String code = codes == null || codes.length == 0 ? "INVALID_VALUE" : codes[0];
		String message = error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage();
		return new ApiViolation(field, code, message);
	}

	private ProblemDetail problem(HttpStatus status, String detail, ApiErrorCode errorCode,
			List<ApiViolation> violations) {
		return ApiProblems.create(status, detail, errorCode, violations);
	}
}
