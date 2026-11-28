package com.captureu.domain.analysis.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.captureu.domain.analysis.entity.AccountSnapshot;
import com.captureu.domain.analysis.entity.Analysis;
import com.captureu.domain.analysis.entity.AnalysisReference;
import com.captureu.domain.analysis.entity.AnalysisStatus;
import com.captureu.domain.analysis.entity.AudienceDemographic;
import com.captureu.domain.analysis.entity.IgMedia;
import com.captureu.domain.analysis.entity.MediaMetricSnapshot;
import com.captureu.domain.analysis.repository.AccountSnapshotRepository;
import com.captureu.domain.analysis.repository.AnalysisReferenceRepository;
import com.captureu.domain.analysis.repository.AnalysisRepository;
import com.captureu.domain.analysis.repository.AudienceDemographicRepository;
import com.captureu.domain.analysis.repository.IgMediaRepository;
import com.captureu.domain.analysis.repository.MediaMetricSnapshotRepository;
import com.captureu.domain.analysis.service.OwnAccountCollector.CollectedAccount;
import com.captureu.domain.analysis.service.ReferenceCollector.CollectedReference;
import com.captureu.domain.instagram.client.ReferenceAccountReader.ReferenceMedia;
import com.captureu.domain.instagram.dto.MediaItem;

/** 수집한 데이터를 한 트랜잭션으로 저장한다. */
@Component
public class AnalysisWriter {

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

		// 게시물 행은 하나지만, 지표 스냅샷은 "어느 계정의 게시물로 수집했는지"별로 따로 저장한다.
		// 협업 게시물은 내 계정과 참고 계정 모두의 게시물로 오므로 두 계정 비교에 모두 들어가야 한다.
		Map<String, OwnedMedia> mediaById = new LinkedHashMap<>();
		List<MediaMetricSnapshot> snapshots = new ArrayList<>();
		Set<String> ownSeen = new HashSet<>();
		for (var m : own.media()) {
			if (ownSeen.add(m.item().id())) {
				mediaById.put(m.item().id(), new OwnedMedia(owner, m.item(), true));
				snapshots.add(MediaMetricSnapshot.of(analysisId, owner, m.item(), m.insights(), collectedAt));
			}
		}

		List<AnalysisReference> referenceResults = new ArrayList<>();
		// 아이디를 바꾼 계정은 다른 아이디로 같은 계정이 될 수 있다. 내 계정이나 이미 넣은 계정과 같으면 건너뛴다
		Set<String> accounts = new HashSet<>(Set.of(owner));
		for (CollectedReference ref : references) {
			if (!ref.succeeded()) {
				referenceResults.add(AnalysisReference.failed(analysisId, ref.username(), ref.failureType(),
						ref.failureMessage(), ref.apiCallCount(), ref.durationMs(), false));
				continue;
			}
			var profile = ref.data().profile();
			String account = referenceAccountId(profile.igId(), ref.username());
			if (!accounts.add(account)) {
				referenceResults.add(AnalysisReference.failed(analysisId, ref.username(), "DUPLICATE_ACCOUNT",
						"내 계정 또는 다른 참고 계정과 같은 계정", ref.apiCallCount(), ref.durationMs(), ref.cacheHit()));
				continue;
			}
			Set<String> refSeen = new HashSet<>();
			// 캐시에서 꺼낸 결과는 받은 시각이 과거다. 게시 후 경과 시간 등이 어긋나지 않게 실제로 받은 시각을 쓴다
			Instant refCollectedAt = ref.fetchedAt() != null ? ref.fetchedAt() : collectedAt;
			for (ReferenceMedia rm : ref.data().media()) {
				if (refSeen.add(rm.item().id())) {
					mediaById.putIfAbsent(rm.item().id(), new OwnedMedia(account, rm.item(), false));
					snapshots.add(
							MediaMetricSnapshot.ofReference(analysisId, account, rm.item(), rm.viewCount(), refCollectedAt));
				}
			}
			referenceResults.add(AnalysisReference.done(analysisId, ref.username(), profile.igId(),
					profile.followersCount(), refSeen.size(), ref.apiCallCount(), ref.durationMs(), ref.cacheHit()));
		}
		List<OwnedMedia> allMedia = new ArrayList<>(mediaById.values());

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
			// 참고 계정을 통해 먼저 저장된 게시물이 사실 내 게시물이면 소유자를 바로잡는다 (협업 게시물 등)
			if (m.mine()) {
				media.claimOwner(m.owner());
			}
			return media;
		}).toList();
		mediaRepository.saveAll(toSave);
	}

	/** 참고 계정 ID가 응답에 없으면 아이디로 구분한다. */
	static String referenceAccountId(String igId, String username) {
		return igId != null ? igId : "@" + username;
	}

	private record OwnedMedia(String owner, MediaItem item, boolean mine) {
	}
}
