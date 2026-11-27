package com.captureu.analysis;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.captureu.analysis.OutlierCalculator.AccountOutliers;
import com.captureu.analysis.OutlierCalculator.ScoredPost;
import com.captureu.analysis.OutlierService.AccountResult;
import com.captureu.analysis.OutlierService.AnalysisOutliers;

/**
 * 비교 리포트. 내 계정과 참고 계정의 아웃라이어 카드, 내 계정의 스킵률·저장률 요약을 한 번에 돌려준다.
 * 저장하지 않고 요청할 때마다 분석 결과에서 계산한다.
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

	/** 계정마다 보여 줄 아웃라이어 카드 수. */
	static final int MAX_CARDS = 5;

	private final OutlierService outlierService;
	private final AnalysisReferenceRepository analysisReferenceRepository;
	private final MediaMetricSnapshotRepository snapshotRepository;
	private final IgMediaRepository mediaRepository;

	ReportService(OutlierService outlierService, AnalysisReferenceRepository analysisReferenceRepository,
			MediaMetricSnapshotRepository snapshotRepository, IgMediaRepository mediaRepository) {
		this.outlierService = outlierService;
		this.analysisReferenceRepository = analysisReferenceRepository;
		this.snapshotRepository = snapshotRepository;
		this.mediaRepository = mediaRepository;
	}

	public AnalysisReport build(Long analysisId) {
		AnalysisOutliers outliers = outlierService.compute(analysisId);
		// 협업 게시물은 계정마다 스냅샷이 따로 있으므로 (계정, 게시물)로 찾는다
		Map<String, MediaMetricSnapshot> snapshots = snapshotRepository.findByAnalysisId(analysisId)
			.stream()
			.collect(Collectors.toMap(s -> key(s.getAccountIgId(), s.getMediaId()), Function.identity()));
		Map<String, IgMedia> media = mediaRepository
			.findAllById(snapshots.values().stream().map(MediaMetricSnapshot::getMediaId).distinct().toList())
			.stream()
			.collect(Collectors.toMap(IgMedia::getId, Function.identity()));

		AccountReport own = toAccountReport(outliers.own(), snapshots, media);
		List<AccountReport> references = outliers.references()
			.stream()
			.map(r -> toAccountReport(r, snapshots, media))
			.toList();
		List<FailedReference> failed = analysisReferenceRepository.findByAnalysisIdOrderByIdAsc(analysisId)
			.stream()
			.filter(r -> r.getStatus() != AnalysisStatus.DONE)
			.map(r -> new FailedReference(r.getUsername(), r.getFailureType()))
			.toList();
		return new AnalysisReport(analysisId, own,
				ownSummary(outliers.own().accountKey(), outliers.own().outliers(), snapshots), references, failed);
	}

	private static String key(String account, String mediaId) {
		return account + "|" + mediaId;
	}

	private AccountReport toAccountReport(AccountResult result, Map<String, MediaMetricSnapshot> snapshots,
			Map<String, IgMedia> media) {
		AccountOutliers o = result.outliers();
		List<PostCard> cards = o.outliers()
			.stream()
			.limit(MAX_CARDS)
			.map(p -> toCard(p, snapshots.get(key(result.accountKey(), p.mediaId())), media.get(p.mediaId())))
			.filter(Objects::nonNull)
			.toList();
		return new AccountReport(result.username(), result.mine(), o.judgedCount(), o.excludedCount(),
				o.medianViews(), o.meanViews(), o.outliers().size(), cards, o.youngMediaIds().size(), o.lowSample());
	}

	private static PostCard toCard(ScoredPost p, MediaMetricSnapshot s, IgMedia m) {
		if (s == null || m == null) {
			return null;
		}
		return new PostCard(m.getId(), m.getPermalink(), m.getThumbnailUrl(), m.getThumbnailUrlExpiresAt(),
				m.getCaptionFirstLine(), m.getPostedAt(), p.views(), p.multiple(), p.commentRate(), s.getLikeCount(),
				s.getCommentsCount(), s.getSaved(), s.getShares(), s.getSkipRate(), s.getAvgWatchTimeMs());
	}

	/**
	 * 내 계정 요약. 아웃라이어와 나머지 게시물의 스킵률·저장률을 비교한다.
	 * "잘된 게시물은 첫 3초에 덜 넘겨졌나"를 보는 기준값이고, 추천 효과 검증(#14)에서도 쓴다.
	 */
	private OwnSummary ownSummary(String ownAccount, AccountOutliers own, Map<String, MediaMetricSnapshot> snapshots) {
		Set<String> outlierIds = own.outliers().stream().map(ScoredPost::mediaId).collect(Collectors.toSet());
		List<MediaMetricSnapshot> judged = own.posts()
			.stream()
			.map(p -> snapshots.get(key(ownAccount, p.mediaId())))
			.filter(Objects::nonNull)
			.toList();
		List<MediaMetricSnapshot> outlierSnaps = judged.stream().filter(s -> outlierIds.contains(s.getMediaId())).toList();
		List<MediaMetricSnapshot> others = judged.stream().filter(s -> !outlierIds.contains(s.getMediaId())).toList();
		return new OwnSummary(medianOf(judged.stream().map(MediaMetricSnapshot::getSkipRate)),
				medianOf(outlierSnaps.stream().map(MediaMetricSnapshot::getSkipRate)),
				medianOf(others.stream().map(MediaMetricSnapshot::getSkipRate)), medianSaveRate(judged),
				medianSaveRate(outlierSnaps), medianSaveRate(others));
	}

	/** 저장률 = 저장 수 / 조회수 (%). */
	private static Double medianSaveRate(Collection<MediaMetricSnapshot> snaps) {
		return medianOf(snaps.stream()
			.filter(s -> s.getSaved() != null && s.getViews() != null && s.getViews() > 0)
			.map(s -> 100.0 * s.getSaved() / s.getViews()));
	}

	private static Double medianOf(Stream<Double> values) {
		List<Double> list = values.filter(Objects::nonNull).toList();
		return list.isEmpty() ? null : Math.round(OutlierCalculator.median(list) * 100) / 100.0;
	}

	public record AnalysisReport(Long analysisId, AccountReport own, OwnSummary ownSummary,
			List<AccountReport> references, List<FailedReference> failedReferences) {
	}

	/**
	 * @param judgedCount   판정한 릴스 수 (게시 7일 이상)
	 * @param excludedCount 게시 7일 이상인데 조회수가 없어 뺀 릴스 수
	 * @param outlierCount  중앙값의 2배 이상인 릴스 수
	 * @param cards         배수 높은 순 최대 5개
	 * @param youngCount    게시 7일 미만이라 아직 판정하지 않은 릴스 수
	 * @param lowSample     판정한 릴스가 적어 참고용
	 */
	public record AccountReport(String username, boolean mine, int judgedCount, int excludedCount,
			Double medianViews, Double meanViews, int outlierCount, List<PostCard> cards, int youngCount,
			boolean lowSample) {
	}

	/**
	 * 아웃라이어 카드. 썸네일 URL은 며칠 뒤 만료된다(thumbnailExpiresAt). 저장·공유·스킵률은 내 계정만 있다.
	 */
	public record PostCard(String mediaId, String permalink, String thumbnailUrl, Instant thumbnailExpiresAt,
			String captionFirstLine, Instant postedAt, int views, Double multiple, Double commentRate, Integer likes,
			Integer comments, Integer saved, Integer shares, Double skipRate, Integer avgWatchTimeMs) {
	}

	/** 스킵률·저장률(%)의 중앙값. 전체 / 아웃라이어 / 나머지. 값이 없으면 null. */
	public record OwnSummary(Double medianSkipRate, Double outlierMedianSkipRate, Double otherMedianSkipRate,
			Double medianSaveRate, Double outlierMedianSaveRate, Double otherMedianSaveRate) {
	}

	public record FailedReference(String username, String failureType) {
	}
}
