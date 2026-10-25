package ro.demo.ledger_api.ledger;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = ReverseTransactionRequestDeserializer.class)
public record ReverseTransactionRequest(
		@Pattern(regexp = ".*\\S.*",
				message = "Description must contain a non-whitespace character")
		@Size(max = 255)
		String description) {
}
