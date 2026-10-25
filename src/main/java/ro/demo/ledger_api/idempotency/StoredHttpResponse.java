package ro.demo.ledger_api.idempotency;

import java.util.Objects;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

public record StoredHttpResponse(int status, String contentType, String body) {
	public StoredHttpResponse {
		HttpStatusCode.valueOf(status);
		MediaType.valueOf(Objects.requireNonNull(contentType, "contentType"));
		Objects.requireNonNull(body, "body");
	}
}
