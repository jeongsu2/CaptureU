package com.captureu.analysis;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface AnalysisRepository extends JpaRepository<Analysis, Long> {

	List<Analysis> findByStatus(AnalysisStatus status);
}

interface AccountSnapshotRepository extends JpaRepository<AccountSnapshot, Long> {
}

interface IgMediaRepository extends JpaRepository<IgMedia, String> {
}

interface MediaMetricSnapshotRepository extends JpaRepository<MediaMetricSnapshot, Long> {

	List<MediaMetricSnapshot> findByAnalysisId(Long analysisId);

	long countByAnalysisIdAndSkipRateIsNotNull(Long analysisId);
}

interface AudienceDemographicRepository extends JpaRepository<AudienceDemographic, Long> {

	List<AudienceDemographic> findByAnalysisId(Long analysisId);
}

interface AnalysisReferenceRepository extends JpaRepository<AnalysisReference, Long> {

	List<AnalysisReference> findByAnalysisIdOrderByIdAsc(Long analysisId);
}
