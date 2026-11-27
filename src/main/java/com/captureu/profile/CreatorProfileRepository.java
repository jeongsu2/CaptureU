package com.captureu.profile;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CreatorProfileRepository extends JpaRepository<CreatorProfile, String> {

	/** 처음 저장하는 요청 두 개가 겹쳐도 PK 중복 없이 한 행으로 합친다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			INSERT INTO creator_profile (ig_user_id, main_concept, sub_concept, updated_at)
			VALUES (:owner, :main, :sub, :now)
			ON DUPLICATE KEY UPDATE main_concept = VALUES(main_concept), sub_concept = VALUES(sub_concept),
			                        updated_at = VALUES(updated_at)
			""", nativeQuery = true)
	void upsert(@Param("owner") String owner, @Param("main") String main, @Param("sub") String sub,
			@Param("now") Instant now);
}
