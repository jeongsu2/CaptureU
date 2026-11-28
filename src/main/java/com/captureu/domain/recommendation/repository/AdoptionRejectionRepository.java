package com.captureu.domain.recommendation.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.captureu.domain.recommendation.entity.AdoptionRejection;

public interface AdoptionRejectionRepository extends JpaRepository<AdoptionRejection, AdoptionRejection.Key> {

	/** "아니에요"를 두 번 눌러도(동시에 와도) 한 행만 남긴다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "INSERT IGNORE INTO adoption_rejection (adoption_id, media_id, rejected_at) VALUES (:adoption, :media, :now)",
			nativeQuery = true)
	void insertIgnore(@Param("adoption") Long adoptionId, @Param("media") String mediaId, @Param("now") Instant now);
}
