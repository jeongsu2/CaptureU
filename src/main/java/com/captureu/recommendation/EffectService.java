package com.captureu.recommendation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.captureu.analysis.CaptionHook.HookType;
import com.captureu.analysis.OutlierCalculator;
import com.captureu.analysis.OwnPostsService;
import com.captureu.analysis.OwnPostsService.OwnPost;
import com.captureu.analysis.ReportService;
import com.captureu.analysis.ReportService.AnalysisReport;
import com.captureu.recommendation.RecommendationAdoption.MatchStatus;

/**
 * 채택한 추천을 실제로 쓴 게시물을 찾고, 그 게시물 성과를 내 계정 기준값(중앙값)과 비교한다 (#14).
 * <p>
 * 분석할 때마다 새 게시물 캡션 첫 줄을 채택 문구와 비교해, 충분히 비슷하면 자동으로 연결하고
 * 애매하면 "이 게시물에 썼나요?"로 묻는다. 인과(추천 덕분인지)는 단정하지 않고 화면에 한계를 함께 적는다.
 */
@Service
public class EffectService {

	/** 채택 직전에 미리 올린 게시물은 아니라고 본다. 시계 차이만 조금 봐준다. */
	private static final Duration POSTED_BEFORE_ADOPTION_TOLERANCE = Duration.ofHours(1);
	static final int MIN_MATURE_SAMPLE = 3;

	private final OwnPostsService ownPostsService;
	private final ReportService reportService;
	private final RecommendationAdoptionRepository adoptionRepository;
	private final Clock clock;

	EffectService(OwnPostsService ownPostsService, ReportService reportService,
			RecommendationAdoptionRepository adoptionRepository, Clock clock) {
		this.ownPostsService = ownPostsService;
		this.reportService = reportService;
		this.adoptionRepository = adoptionRepository;
		this.clock = clock;
	}

	/** 이 분석 시점의 게시물로 채택을 연결하고 효과를 계산한다. 새로 자동 연결된 것은 저장한다. */
	@Transactional
	public EffectReport effects(Long analysisId) {
		List<OwnPost> posts = ownPostsService.load(analysisId);
		AnalysisReport report = reportService.build(analysisId);
		Baseline baseline = new Baseline(report.own().medianViews(),
				report.ownSummary() == null ? null : report.ownSummary().medianSkipRate(),
				report.ownSummary() == null ? null : report.ownSummary().medianSaveRate());
		Map<String, OwnPost> byId = posts.stream().collect(Collectors.toMap(OwnPost::mediaId, Function.identity()));

		List<RecommendationAdoption> adoptions = adoptionRepository.findAllByOrderByAdoptedAtAsc();
		Set<String> taken = new HashSet<>();
		adoptions.stream().filter(RecommendationAdoption::isMatched).forEach(a -> taken.add(a.getMatchedMediaId()));

		List<AdoptionEffect> items = new ArrayList<>();
		for (RecommendationAdoption adoption : adoptions) {
			Candidate candidate = null;
			if (!adoption.isMatched()) {
				Candidate best = bestCandidate(adoption, posts, taken);
				if (best != null && best.score() >= AdoptionMatcher.AUTO) {
					adoption.match(MatchStatus.AUTO, best.mediaId(), best.score(), Instant.now(clock));
					taken.add(best.mediaId());
				}
				else if (best != null && best.score() >= AdoptionMatcher.ASK) {
					candidate = best;
				}
			}
			items.add(toEffect(adoption, byId, baseline, candidate));
		}
		return new EffectReport(analysisId, baseline, summarize(items, baseline), items);
	}

	/** "이 게시물에 썼어요"(used=true) 또는 "아니에요"(used=false). 게시물은 이 분석의 내 게시물이어야 한다. */
	@Transactional
	public void decide(Long adoptionId, Long analysisId, String mediaId, boolean used) {
		RecommendationAdoption adoption = adoptionRepository.findById(adoptionId)
			.orElseThrow(() -> new AdoptionNotFoundException(adoptionId));
		boolean ownPost = ownPostsService.load(analysisId).stream().anyMatch(p -> p.mediaId().equals(mediaId));
		if (!ownPost) {
			throw new IllegalArgumentException("이 분석의 내 게시물이 아니에요");
		}
		if (used && adoptionRepository.findAllByOrderByAdoptedAtAsc()
			.stream()
			.anyMatch(a -> !a.getId().equals(adoptionId) && a.isMatched() && mediaId.equals(a.getMatchedMediaId()))) {
			throw new IllegalArgumentException("이 게시물은 이미 다른 채택 문구에 연결돼 있어요");
		}
		adoption.match(used ? MatchStatus.CONFIRMED : MatchStatus.REJECTED, mediaId, adoption.getMatchScore(),
				Instant.now(clock));
	}

	private static Candidate bestCandidate(RecommendationAdoption adoption, List<OwnPost> posts, Set<String> taken) {
		Instant earliest = adoption.getAdoptedAt().minus(POSTED_BEFORE_ADOPTION_TOLERANCE);
		String rejected = adoption.getMatchStatus() == MatchStatus.REJECTED ? adoption.getMatchedMediaId() : null;
		Candidate best = null;
		for (OwnPost p : posts) {
			if (p.postedAt().isBefore(earliest) || taken.contains(p.mediaId()) || p.mediaId().equals(rejected)) {
				continue;
			}
			double score = Math.max(AdoptionMatcher.similarity(adoption.getHookText(), p.captionHook()),
					AdoptionMatcher.similarity(adoption.getHookText(), p.captionFirstLine()));
			if (best == null || score > best.score()) {
				best = new Candidate(p.mediaId(), p.permalink(), p.thumbnailUrl(), p.captionFirstLine(), p.postedAt(),
						score);
			}
		}
		return best;
	}

	private static AdoptionEffect toEffect(RecommendationAdoption a, Map<String, OwnPost> byId, Baseline baseline,
			Candidate candidate) {
		MatchedPost post = null;
		String state;
		if (a.isMatched()) {
			state = a.getMatchStatus().name();
			OwnPost p = byId.get(a.getMatchedMediaId());
			post = p == null ? MatchedPost.missing(a.getMatchedMediaId(), a.getMatchScore())
					: MatchedPost.of(p, baseline, a.getMatchScore());
		}
		else {
			state = candidate != null ? "ASK" : "NOT_FOUND";
		}
		return new AdoptionEffect(a.getId(), a.getRecommendationId(), a.getHookText(), a.getHookType(),
				a.getAdoptedAt(), state, post, candidate);
	}

	static EffectSummary summarize(List<AdoptionEffect> items, Baseline baseline) {
		List<MatchedPost> matched = items.stream().map(AdoptionEffect::post).filter(Objects::nonNull).toList();
		List<MatchedPost> mature = matched.stream().filter(MatchedPost::mature).toList();
		Double skipDiff = average(mature.stream().map(MatchedPost::skipRateDiff).toList());
		Double saveDiff = average(mature.stream().map(MatchedPost::saveRateDiff).toList());
		List<Double> multiples = mature.stream().map(MatchedPost::viewsMultiple).filter(Objects::nonNull).toList();
		Double viewsMultiple = multiples.isEmpty() ? null : round(OutlierCalculator.median(multiples));
		boolean lowSample = mature.size() < MIN_MATURE_SAMPLE;

		List<String> notes = new ArrayList<>();
		notes.add("추천을 쓴 게시물은 주제·시기·유행도 달라서, 차이를 모두 추천 덕분이라고 단정할 수는 없어요.");
		notes.add("스킵률·조회수는 분석한 시점까지 쌓인 값이에요. 게시 7일이 지난 게시물만 평균에 넣어요.");
		if (matched.size() > mature.size()) {
			notes.add("게시 7일이 안 된 게시물 %d개는 참고로만 보여 줘요.".formatted(matched.size() - mature.size()));
		}
		if (!mature.isEmpty() && lowSample) {
			notes.add("비교한 게시물이 %d개라 참고용이에요. %d개 이상 쌓이면 더 믿을 만해요."
				.formatted(mature.size(), MIN_MATURE_SAMPLE));
		}
		return new EffectSummary(items.size(), matched.size(), mature.size(), skipDiff, saveDiff, viewsMultiple,
				lowSample, headline(mature.size(), skipDiff, baseline), notes);
	}

	private static String headline(int matureCount, Double skipDiff, Baseline baseline) {
		if (matureCount == 0 || skipDiff == null || baseline.medianSkipRate() == null) {
			return null;
		}
		String direction = skipDiff <= 0 ? "낮아요" : "높아요";
		return "추천을 쓴 게시물 %d개의 스킵률이 내 중앙값(%.2f%%)보다 평균 %.2f%%p %s".formatted(matureCount,
				baseline.medianSkipRate(), Math.abs(skipDiff), direction);
	}

	private static Double average(List<Double> values) {
		List<Double> present = values.stream().filter(Objects::nonNull).toList();
		return present.isEmpty() ? null
				: round(present.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
	}

	static Double round(Double v) {
		return v == null ? null : Math.round(v * 100) / 100.0;
	}

	/** 비교 기준: 이 분석의 내 계정 중앙값 (조회수는 게시 7일 이상 릴스, 스킵률·저장률은 판정한 릴스). */
	public record Baseline(Double medianViews, Double medianSkipRate, Double medianSaveRate) {
	}

	/**
	 * @param status ASK면 candidate로 "이 게시물에 썼나요?"를 묻는다. NOT_FOUND는 아직 비슷한 게시물이 없음
	 */
	public record AdoptionEffect(Long adoptionId, Long recommendationId, String hookText, HookType hookType,
			Instant adoptedAt, String status, MatchedPost post, Candidate candidate) {
	}

	public record Candidate(String mediaId, String permalink, String thumbnailUrl, String captionFirstLine,
			Instant postedAt, double score) {
	}

	/**
	 * @param mature        게시 7일이 지나 비교에 넣는 게시물인지
	 * @param skipRateDiff  내 중앙값 대비 %p (음수면 덜 넘겨짐)
	 * @param viewsMultiple 내 조회수 중앙값 대비 배수
	 * @param inAnalysis    이 분석에서 게시물 지표를 받았는지 (지운 게시물 등은 false)
	 */
	public record MatchedPost(String mediaId, String permalink, String thumbnailUrl, String captionFirstLine,
			Instant postedAt, Integer ageHours, boolean mature, Integer views, Double viewsMultiple, Double skipRate,
			Double skipRateDiff, Double saveRate, Double saveRateDiff, Double matchScore, boolean inAnalysis) {

		static MatchedPost of(OwnPost p, Baseline b, Double score) {
			boolean mature = p.reels() && p.ageHours() >= OutlierCalculator.MATURE_AGE_HOURS;
			Double multiple = p.views() == null || b.medianViews() == null || b.medianViews() == 0 ? null
					: round(p.views() / b.medianViews());
			Double skipDiff = p.skipRate() == null || b.medianSkipRate() == null ? null
					: round(p.skipRate() - b.medianSkipRate());
			Double saveRate = round(p.saveRate());
			Double saveDiff = saveRate == null || b.medianSaveRate() == null ? null
					: round(saveRate - b.medianSaveRate());
			return new MatchedPost(p.mediaId(), p.permalink(), p.thumbnailUrl(), p.captionFirstLine(), p.postedAt(),
					p.ageHours(), mature, p.views(), multiple, p.skipRate(), skipDiff, saveRate, saveDiff, score, true);
		}

		static MatchedPost missing(String mediaId, Double score) {
			return new MatchedPost(mediaId, null, null, null, null, null, false, null, null, null, null, null, null,
					score, false);
		}
	}

	/**
	 * @param headline 예: "추천을 쓴 게시물 3개의 스킵률이 내 중앙값(63.75%)보다 평균 4.20%p 낮아요". 비교할 게시물이 없으면 null
	 * @param lowSample 게시 7일이 지난 연결 게시물이 3개 미만이라 참고용
	 */
	public record EffectSummary(int adoptedCount, int matchedCount, int matureCount, Double avgSkipRateDiff,
			Double avgSaveRateDiff, Double medianViewsMultiple, boolean lowSample, String headline,
			List<String> notes) {
	}

	public record EffectReport(Long analysisId, Baseline baseline, EffectSummary summary,
			List<AdoptionEffect> adoptions) {
	}
}
