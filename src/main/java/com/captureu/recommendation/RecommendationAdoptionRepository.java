package com.captureu.recommendation;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface RecommendationAdoptionRepository extends JpaRepository<RecommendationAdoption, Long> {

	List<RecommendationAdoption> findByRecommendationIdOrderByIdAsc(Long recommendationId);
}
