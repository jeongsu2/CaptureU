package com.captureu.domain.reference.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.captureu.domain.reference.entity.ReferenceAccount;

public interface ReferenceAccountRepository extends JpaRepository<ReferenceAccount, Long> {

	List<ReferenceAccount> findByOwnerIgUserIdOrderByIdAsc(String ownerIgUserId);

	/** 한 번의 DELETE 문으로 지운다 (조회 후 하나씩 지우는 방식은 동시 요청에서 낙관적 락 예외가 난다). */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from ReferenceAccount r where r.ownerIgUserId = :owner")
	int deleteAllByOwner(@Param("owner") String ownerIgUserId);
}
