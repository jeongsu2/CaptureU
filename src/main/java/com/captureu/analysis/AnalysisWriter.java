package com.captureu.analysis;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.captureu.analysis.OwnAccountCollector.CollectedAccount;
import com.captureu.analysis.OwnAccountCollector.CollectedMedia;

/** 수집한 데이터를 한 트랜잭션으로 저장한다. */
@Component
class AnalysisWriter {

	static final String ABANDONED = "ABANDONED";

	private final AnalysisRepository analysisRepository;
	private final AccountSnapshotRepository accountSnapshotRepository;
	private final IgMediaRepository mediaRepository;
	private final MediaMetricSnapshotRepository snapshotRepository;
	private final AudienceDemographicRepository demographicRepository;

	AnalysisWriter(AnalysisRepository analysisRepository, AccountSnapshotRepository accountSnapshotRepository,
			IgMediaRepository mediaRepository, MediaMetricSnapshotRepository snapshotRepository,
			AudienceDemographicRepository demographicRepository) {
		this.analysisRepository = analysisRepository;
		this.accountSnapshotRepository = accountSnapshotRepository;
		this.mediaRepository = mediaRepository;
		this.snapshotRepository = snapshotRepository;
		this.demographicRepository = demographicRepository;
	}

	@Transactional
	Analysis start(String igUserId, Instant now) {
		return analysisRepository.save(Analysis.start(igUserId, now));
	}

	@Transactional
	Analysis complete(Long analysisId, CollectedAccount data, int apiCallCount, Instant collectedAt, Instant now) {
		Analysis analysis = analysisRepository.findById(analysisId).orElseThrow();
		String owner = analysis.getIgUserId();

		accountSnapshotRepository.save(new AccountSnapshot(analysisId, owner, data.account().username(),
				data.account().followersCount(), data.account().reportedMediaCount()));

		upsertMedia(owner, data.media(), now);

		snapshotRepository.saveAll(data.media()
			.stream()
			.map(m -> MediaMetricSnapshot.of(analysisId, m.item(), m.insights(), collectedAt))
			.toList());

		demographicRepository.saveAll(data.demographics()
			.stream()
			.map(row -> AudienceDemographic.of(analysisId, row))
			.toList());

		analysis.complete(data.media().size(), data.mediaTruncated(), data.insightFailedCount(), apiCallCount, now);
		return analysis;
	}

	@Transactional
	Analysis fail(Long analysisId, String failureType, String failureMessage, int apiCallCount, Instant now) {
		Analysis analysis = analysisRepository.findById(analysisId).orElseThrow();
		analysis.fail(failureType, failureMessage, apiCallCount, now);
		return analysis;
	}

	/**
	 * '수집 중'으로 남은 분석을 실패로 정리한다. 서버가 수집 도중 꺼지면 이런 행이 남는다.
	 * 지금은 서버가 한 대라서, 시작할 때 남아 있는 '수집 중'은 모두 중단된 것이다.
	 */
	@Transactional
	int abandonCollecting(Instant now) {
		List<Analysis> stale = analysisRepository.findByStatus(AnalysisStatus.COLLECTING);
		stale.forEach(a -> a.fail(ABANDONED, "서버가 수집 도중 중단됨", a.getApiCallCount(), now));
		return stale.size();
	}

	private void upsertMedia(String owner, List<CollectedMedia> collected, Instant now) {
		List<String> ids = collected.stream().map(m -> m.item().id()).toList();
		Map<String, IgMedia> existing = mediaRepository.findAllById(ids)
			.stream()
			.collect(Collectors.toMap(IgMedia::getId, Function.identity()));
		List<IgMedia> toSave = collected.stream().map(m -> {
			IgMedia media = existing.get(m.item().id());
			if (media == null) {
				return IgMedia.from(owner, m.item(), now);
			}
			media.update(m.item(), now);
			return media;
		}).toList();
		mediaRepository.saveAll(toSave);
	}
}
