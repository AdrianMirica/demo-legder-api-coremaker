package ro.demo.ledger_api.shared.api;

public record ApiViolation(String field, String code, String message) {
}
