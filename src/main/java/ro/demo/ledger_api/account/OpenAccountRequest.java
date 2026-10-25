package ro.demo.ledger_api.account;

import java.math.BigDecimal;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = OpenAccountRequestDeserializer.class)
public record OpenAccountRequest(String currency, BigDecimal overdraftLimit) {
}
