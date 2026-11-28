package com.captureu.domain.analysis.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.captureu.domain.analysis.entity.AudienceDemographic;

public interface AudienceDemographicRepository extends JpaRepository<AudienceDemographic, Long> {

	List<AudienceDemographic> findByAnalysisId(Long analysisId);
}
