package com.captureu.analysis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.captureu.analysis.OwnAccountCollector.CollectedAccount;
import com.captureu.analysis.ReferenceCollector.CollectedReference;
import com.captureu.instagram.account.MediaItem;
import com.captureu.instagram.account.ReferenceAccountReader.ReferenceMedia;

/** 수집한 데이터를 한 트랜잭션으로 저장한다. */
@Component
class AnalysisWriter {

	static final String ABANDONED = "ABANDONED";

	private final AnalysisRepository analysisRepository;
	private final AccountSnapshotRepository accountSnapshotRepository;
	private final IgMediaRepository mediaRepository;
	private final MediaMetricSnapshotRepository snapshotRepository;
	private final AudienceDemographicRepository demographicRepository;
	private final AnalysisReferenceRepository analysisReferenceRepository;

	AnalysisWriter(AnalysisRepository analysisRepository, AccountSnapshotRepository accountSnapshotRepository,
			IgMediaRepository mediaRepository, MediaMetricSnapshotRepository snapshotRepository,
			AudienceDemographicRepository demographicRepository,
			AnalysisReferenceRepository analysisReferenceRepository) {
		this.analysisRepository = analysisRepository;
		this.accountSnapshotRepository = accountSnapshotRepository;
		this.mediaRepository = mediaRepository;
		this.snapshotRepository = snapshotRepository;
		this.demographicRepository = demographicRepository;
		this.analysisReferenceRepository = analysisReferenceRepository;
	}

	@Transactional
	Analysis start(String igUserId, Instant now) {
		return analysisRepository.save(Analysis.start(igUserId, now));
	}

	@Transactional
	Analysis complete(Long analysisId, CollectedAccount own, List<CollectedReference> references, int apiCallCount,
			Instant collectedAt, Instant now) {
		Analysis analysis = analysisRepository.findById(analysisId).orElseThrow();
		String owner = analysis.getIgUserId();

		accountSnapshotRepository.save(new AccountSnapshot(analysisId, owner, own.account().username(),
				own.account().followersCount(), own.account().reportedMediaCount()));

		List<OwnedMedia> allMedia = new ArrayList<>();
		List<MediaMetricSnapshot> snapshots = new ArrayList<>();
		// 같은 게시물이 두 번 들어가면 (분석, 게시물) 유니크 키가 깨지므로 한 분석 안에서 한 번만 저장한다
		Set<String> seen = new HashSet<>();
		for (var m : own.media()) {
			if (seen.add(m.item().id())) {
				allMedia.add(new OwnedMedia(owner, m.item()));
				snapshots.add(MediaMetricSnapshot.of(analysisId, m.item(), m.insights(), collectedAt));
			}
		}

		List<AnalysisReference> referenceResults = new ArrayList<>();
		for (CollectedReference ref : references) {
			if (!ref.succeeded()) {
				referenceResults.add(
						AnalysisReference.failed(analysisId, ref.username(), ref.failureType(), ref.failureMessage()));
				continue;
			}
			var profile = ref.data().profile();
			String refOwner = profile.igId() != null ? profile.igId() : "@" + ref.username();
			int saved = 0;
			for (ReferenceMedia rm : ref.data().media()) {
				if (seen.add(rm.item().id())) {
					allMedia.add(new OwnedMedia(refOwner, rm.item()));
					snapshots.add(MediaMetricSnapshot.ofReference(analysisId, rm.item(), rm.viewCount(), collectedAt));
					saved++;
				}
			}
			referenceResults.add(AnalysisReference.done(analysisId, ref.username(), profile.igId(),
					profile.followersCount(), saved));
		}

		upsertMedia(allMedia, now);
		snapshotRepository.saveAll(snapshots);
		analysisReferenceRepository.saveAll(referenceResults);
		demographicRepository.saveAll(own.demographics()
			.stream()
			.map(row -> AudienceDemographic.of(analysisId, row))
			.toList());

		analysis.complete(own.media().size(), own.mediaTruncated(), own.insightFailedCount(), apiCallCount, now);
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

	private void upsertMedia(List<OwnedMedia> collected, Instant now) {
		List<String> ids = collected.stream().map(m -> m.item().id()).toList();
		Map<String, IgMedia> existing = mediaRepository.findAllById(ids)
			.stream()
			.collect(Collectors.toMap(IgMedia::getId, Function.identity()));
		List<IgMedia> toSave = collected.stream().map(m -> {
			IgMedia media = existing.get(m.item().id());
			if (media == null) {
				return IgMedia.from(m.owner(), m.item(), now);
			}
			media.update(m.item(), now);
			return media;
		}).toList();
		mediaRepository.saveAll(toSave);
	}

	private record OwnedMedia(String owner, MediaItem item) {
	}
}
