package com.captureu.analysis;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 한 건에서 내 계정 게시물과 그 시점 지표를 돌려준다 (#14).
 * 채택한 추천 문구를 실제로 쓴 게시물을 찾고, 그 게시물 성과를 내 기준값과 비교하는 데 쓴다.
 */
@Service
@Transactional(readOnly = true)
public class OwnPostsService {

	private final AnalysisRepository analysisRepository;
	private final MediaMetricSnapshotRepository snapshotRepository;
	private final IgMediaRepository mediaRepository;

	OwnPostsService(AnalysisRepository analysisRepository, MediaMetricSnapshotRepository snapshotRepository,
			IgMediaRepository mediaRepository) {
		this.analysisRepository = analysisRepository;
		this.snapshotRepository = snapshotRepository;
		this.mediaRepository = mediaRepository;
	}

	/** 게시 시각이 최근인 순. 분석이 끝나지 않았으면 예외 (리포트와 같은 규칙). */
	public List<OwnPost> load(Long analysisId) {
		Analysis analysis = analysisRepository.findById(analysisId)
			.orElseThrow(() -> new AnalysisNotFoundException(analysisId));
		if (analysis.getStatus() != AnalysisStatus.DONE) {
			throw new AnalysisNotReadyException(analysisId, analysis.getStatus());
		}
		List<MediaMetricSnapshot> snapshots = snapshotRepository.findByAnalysisId(analysisId)
			.stream()
			.filter(s -> analysis.getIgUserId().equals(s.getAccountIgId()))
			.toList();
		Map<String, IgMedia> media = mediaRepository
			.findAllById(snapshots.stream().map(MediaMetricSnapshot::getMediaId).toList())
			.stream()
			.collect(Collectors.toMap(IgMedia::getId, Function.identity()));
		return snapshots.stream()
			.filter(s -> media.containsKey(s.getMediaId()))
			.map(s -> OwnPost.of(media.get(s.getMediaId()), s))
			.sorted(Comparator.comparing(OwnPost::postedAt).reversed())
			.toList();
	}

	/**
	 * @param ageHours 분석 시점에 게시 후 지난 시간
	 * @param reels    릴스인지 (조회수·스킵률은 릴스만 비교한다)
	 */
	public record OwnPost(String mediaId, Instant postedAt, String captionFirstLine, String captionHook,
			String permalink, String thumbnailUrl, boolean reels, int ageHours, Integer views, Double skipRate,
			Integer saved) {

		static OwnPost of(IgMedia m, MediaMetricSnapshot s) {
			return new OwnPost(m.getId(), m.getPostedAt(), m.getCaptionFirstLine(), m.getCaptionHook(),
					m.getPermalink(), m.getThumbnailUrl(), "REELS".equals(m.getMediaProductType()), s.getAgeHours(),
					s.getViews(), s.getSkipRate(), s.getSaved());
		}

		/** 저장률 = 저장 수 / 조회수 (%). 리포트와 같은 정의. */
		public Double saveRate() {
			return saved == null || views == null || views == 0 ? null : 100.0 * saved / views;
		}
	}
}
