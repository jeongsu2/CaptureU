package com.captureu.domain.analysis.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.captureu.domain.analysis.entity.Analysis;
import com.captureu.domain.analysis.entity.AnalysisStatus;

public interface AnalysisRepository extends JpaRepository<Analysis, Long> {

	List<Analysis> findByStatus(AnalysisStatus status);
}
