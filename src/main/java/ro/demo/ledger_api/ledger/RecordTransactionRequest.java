package ro.demo.ledger_api.ledger;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = RecordTransactionRequestDeserializer.class)
public record RecordTransactionRequest(
		@NotBlank @Size(max = 255) String description,
		@NotNull @Size(min = 2, max = 100) List<@Valid EntryRequest> entries) {
}
