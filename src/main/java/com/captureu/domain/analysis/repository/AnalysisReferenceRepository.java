package com.captureu.domain.analysis.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.captureu.domain.analysis.entity.AnalysisReference;

public interface AnalysisReferenceRepository extends JpaRepository<AnalysisReference, Long> {

	List<AnalysisReference> findByAnalysisIdOrderByIdAsc(Long analysisId);
}
