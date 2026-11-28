package com.captureu.domain.recommendation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.captureu.domain.recommendation.entity.RecommendationAdoption;

public interface RecommendationAdoptionRepository extends JpaRepository<RecommendationAdoption, Long> {

	List<RecommendationAdoption> findByRecommendationIdOrderByIdAsc(Long recommendationId);

	List<RecommendationAdoption> findAllByOrderByAdoptedAtAsc();
}
