package ro.demo.ledger_api.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransactionEntity, String> {
}
