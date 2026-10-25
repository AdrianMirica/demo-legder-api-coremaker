package ro.demo.ledger_api.ledger;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransactionEntity, String> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select transaction from LedgerTransactionEntity transaction where transaction.id = :id")
	Optional<LedgerTransactionEntity> lockById(@Param("id") String id);

	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("""
			select transaction
			from LedgerTransactionEntity transaction
			where transaction.reversedTransactionId = :originalId
			""")
	Optional<LedgerTransactionEntity> findReversalForUpdate(@Param("originalId") String originalId);
}
