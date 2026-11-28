package com.captureu.domain.analysis.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.captureu.domain.analysis.entity.MediaMetricSnapshot;

public interface MediaMetricSnapshotRepository extends JpaRepository<MediaMetricSnapshot, Long> {

	List<MediaMetricSnapshot> findByAnalysisId(Long analysisId);

	long countByAnalysisIdAndSkipRateIsNotNull(Long analysisId);
}
