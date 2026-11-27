package com.captureu.recommendation;

import org.springframework.data.jpa.repository.JpaRepository;

interface RecommendationRepository extends JpaRepository<Recommendation, Long> {
}
