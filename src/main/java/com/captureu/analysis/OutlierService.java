package com.captureu.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.captureu.analysis.OutlierCalculator.AccountOutliers;
import com.captureu.analysis.OutlierCalculator.PostMetric;

/** 분석 한 건의 내 계정과 참고 계정별 아웃라이어를 계산한다. 저장하지 않고 요청할 때마다 계산한다. */
@Service
@Transactional(readOnly = true)
public class OutlierService {

	private final AnalysisRepository analysisRepository;
	private final AccountSnapshotRepository accountSnapshotRepository;
	private final AnalysisReferenceRepository analysisReferenceRepository;
	private final MediaMetricSnapshotRepository snapshotRepository;
	private final IgMediaRepository mediaRepository;

	OutlierService(AnalysisRepository analysisRepository, AccountSnapshotRepository accountSnapshotRepository,
			AnalysisReferenceRepository analysisReferenceRepository, MediaMetricSnapshotRepository snapshotRepository,
			IgMediaRepository mediaRepository) {
		this.analysisRepository = analysisRepository;
		this.accountSnapshotRepository = accountSnapshotRepository;
		this.analysisReferenceRepository = analysisReferenceRepository;
		this.snapshotRepository = snapshotRepository;
		this.mediaRepository = mediaRepository;
	}

	public AnalysisOutliers compute(Long analysisId) {
		Analysis analysis = analysisRepository.findById(analysisId)
			.orElseThrow(() -> new AnalysisNotFoundException(analysisId));
		if (analysis.getStatus() != AnalysisStatus.DONE) {
			throw new AnalysisNotReadyException(analysisId, analysis.getStatus());
		}
		Map<String, List<PostMetric>> byAccount = postsByAccount(analysisId);

		String owner = analysis.getIgUserId();
		String username = accountSnapshotRepository.findById(analysisId).map(AccountSnapshot::getUsername).orElse(null);
		AccountResult own = new AccountResult(owner, username, true,
				OutlierCalculator.calculate(byAccount.getOrDefault(owner, List.of())));

		List<AccountResult> references = new ArrayList<>();
		for (AnalysisReference ref : analysisReferenceRepository.findByAnalysisIdOrderByIdAsc(analysisId)) {
			if (ref.getStatus() != AnalysisStatus.DONE) {
				continue;
			}
			String account = AnalysisWriter.referenceAccountId(ref.getIgId(), ref.getUsername());
			references.add(new AccountResult(account, ref.getUsername(), false,
					OutlierCalculator.calculate(byAccount.getOrDefault(account, List.of()))));
		}
		return new AnalysisOutliers(analysisId, own, references);
	}

	/** 스냅샷을 "어느 계정의 게시물로 수집했는지"로 나눈다 (협업 게시물은 두 계정 모두에 들어간다). */
	private Map<String, List<PostMetric>> postsByAccount(Long analysisId) {
		List<MediaMetricSnapshot> snapshots = snapshotRepository.findByAnalysisId(analysisId);
		Map<String, IgMedia> media = mediaRepository
			.findAllById(snapshots.stream().map(MediaMetricSnapshot::getMediaId).distinct().toList())
			.stream()
			.collect(Collectors.toMap(IgMedia::getId, Function.identity()));
		return snapshots.stream()
			.filter(s -> media.containsKey(s.getMediaId()))
			.collect(Collectors.groupingBy(MediaMetricSnapshot::getAccountIgId,
					Collectors.mapping(s -> new PostMetric(s.getMediaId(),
							media.get(s.getMediaId()).getMediaProductType(), s.getAgeHours(), s.getViews(),
							s.getCommentsCount()), Collectors.toList())));
	}

	public record AnalysisOutliers(Long analysisId, AccountResult own, List<AccountResult> references) {
	}

	/**
	 * @param accountKey 계정 구분값. 보통 인스타 계정 ID이고, 참고 계정 ID를 받지 못했으면 "@아이디"
	 */
	public record AccountResult(String accountKey, String username, boolean mine, AccountOutliers outliers) {
	}
}
