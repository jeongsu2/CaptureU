package com.captureu.domain.recommendation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.captureu.domain.recommendation.entity.Recommendation;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {
}
