package com.captureu.domain.recommendation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.captureu.domain.recommendation.entity.RecommendationAdoption;

public interface RecommendationAdoptionRepository extends JpaRepository<RecommendationAdoption, Long> {

	List<RecommendationAdoption> findByRecommendationIdOrderByIdAsc(Long recommendationId);

	List<RecommendationAdoption> findAllByOrderByAdoptedAtAsc();

	/** 한 인스타 계정의 채택만 (#16). 채택 → 추천 → 분석의 계정으로 거른다. */
	@Query("""
			select a from RecommendationAdoption a, Recommendation r, Analysis an
			where r.id = a.recommendationId and an.id = r.analysisId and an.igUserId = :owner
			order by a.adoptedAt asc""")
	List<RecommendationAdoption> findByOwner(@Param("owner") String owner);
}
