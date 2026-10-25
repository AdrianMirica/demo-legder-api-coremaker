package ro.demo.ledger_api.ledger;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Repository;
import ro.demo.ledger_api.account.AccountEntity;

@Repository
public class AccountLockRepository {
	private final EntityManager entityManager;

	public AccountLockRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	public List<AccountEntity> lockInOrder(List<String> sortedAccountIds) {
		String parameters = java.util.stream.IntStream.range(0, sortedAccountIds.size())
				.mapToObj(index -> "?" + (index + 1))
				.collect(java.util.stream.Collectors.joining(", "));
		var query = entityManager.createNativeQuery("""
				SELECT id, currency, overdraft_limit, balance, settlement_account, created_at
				FROM accounts
				WHERE id IN (%s)
				ORDER BY id
				FOR UPDATE
				""".formatted(parameters), AccountEntity.class);
		for (int index = 0; index < sortedAccountIds.size(); index++) {
			query.setParameter(index + 1, sortedAccountIds.get(index));
		}
		@SuppressWarnings("unchecked")
		List<AccountEntity> accounts = query.getResultList();
		return accounts;
	}
}
