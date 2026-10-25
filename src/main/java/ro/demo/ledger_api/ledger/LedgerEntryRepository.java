package ro.demo.ledger_api.ledger;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, String> {
	List<LedgerEntryEntity> findByTransactionIdOrderBySequenceNoAsc(String transactionId);
}
