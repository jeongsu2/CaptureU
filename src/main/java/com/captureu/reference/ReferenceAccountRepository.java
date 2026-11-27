package com.captureu.reference;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReferenceAccountRepository extends JpaRepository<ReferenceAccount, Long> {

	List<ReferenceAccount> findByOwnerIgUserIdOrderByIdAsc(String ownerIgUserId);

	void deleteByOwnerIgUserId(String ownerIgUserId);
}
