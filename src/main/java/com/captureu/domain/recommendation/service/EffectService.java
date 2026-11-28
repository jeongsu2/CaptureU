package com.captureu.domain.recommendation.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.captureu.domain.analysis.entity.Analysis;
import com.captureu.domain.analysis.exception.AnalysisNotFoundException;
import com.captureu.domain.analysis.repository.AnalysisRepository;
import com.captureu.domain.analysis.service.OutlierCalculator;
import com.captureu.domain.analysis.service.OwnPostsService;
import com.captureu.domain.analysis.service.OwnPostsService.OwnPost;
import com.captureu.domain.analysis.util.CaptionHook.HookType;
import com.captureu.domain.recommendation.entity.AdoptionRejection;
import com.captureu.domain.recommendation.entity.RecommendationAdoption;
import com.captureu.domain.recommendation.exception.AdoptionConflictException;
import com.captureu.domain.recommendation.exception.AdoptionNotFoundException;
import com.captureu.domain.recommendation.exception.InvalidMatchException;
import com.captureu.domain.recommendation.repository.AdoptionRejectionRepository;
import com.captureu.domain.recommendation.repository.RecommendationAdoptionRepository;

/**
 * 채택한 추천을 실제로 쓴 게시물을 찾고, 그 게시물 성과를 내 계정 기준값(중앙값)과 비교한다 (#14).
 * <p>
 * 자동 연결은 저장하지 않고 조회할 때마다 그 분석의 게시물로 다시 계산한다 (조회가 데이터를 바꾸지 않게).
 * 저장하는 것은 사용자의 결정뿐이다: "이 게시물에 썼어요"(확인), "아니에요"(뗀 게시물 목록).
 * 인과(추천 덕분인지)는 단정하지 않고 화면에 한계를 함께 적는다.
 */
@Service
public class EffectService {

	/** 채택 직전에 미리 올린 게시물은 아니라고 본다. 시계 차이만 조금 봐준다. */
	private static final Duration POSTED_BEFORE_ADOPTION_TOLERANCE = Duration.ofHours(1);
	static final int MIN_SAMPLE = 3;
	/** 기준값을 낼 게시물이 이보다 적으면 기준값 자체가 흔들린다. */
	static final int MIN_BASELINE = OutlierCalculator.MIN_SAMPLE;

	private final OwnPostsService ownPostsService;
	private final RecommendationAdoptionRepository adoptionRepository;
	private final AdoptionRejectionRepository rejectionRepository;
	private final AnalysisRepository analysisRepository;
	private final Clock clock;

	EffectService(OwnPostsService ownPostsService, RecommendationAdoptionRepository adoptionRepository,
			AdoptionRejectionRepository rejectionRepository, AnalysisRepository analysisRepository, Clock clock) {
		this.ownPostsService = ownPostsService;
		this.adoptionRepository = adoptionRepository;
		this.rejectionRepository = rejectionRepository;
		this.analysisRepository = analysisRepository;
		this.clock = clock;
	}

	/** 분석의 인스타 계정. 채택은 이 계정 것만 본다 (#16, 여러 사용자). */
	private String ownerOf(Long analysisId) {
		return analysisRepository.findById(analysisId)
			.map(Analysis::getIgUserId)
			.orElseThrow(() -> new AnalysisNotFoundException(analysisId));
	}

	@Transactional(readOnly = true)
	public EffectReport effects(Long analysisId) {
		List<OwnPost> posts = ownPostsService.load(analysisId);
		List<RecommendationAdoption> adoptions = adoptionRepository.findByOwner(ownerOf(analysisId));
		Map<Long, Set<String>> rejected = rejectionRepository.findAll()
			.stream()
			.collect(Collectors.groupingBy(r -> r.getId().adoptionId(),
					Collectors.mapping(r -> r.getId().mediaId(), Collectors.toSet())));
		Assignment assignment = assign(adoptions, posts, rejected);

		// 기준값은 추천을 쓴 게시물을 뺀 나머지로 낸다 (비교 대상이 기준을 끌어당기지 않게)
		Baseline baseline = baseline(posts, assignment.linkedMediaIds());
		Map<String, OwnPost> byId = posts.stream().collect(Collectors.toMap(OwnPost::mediaId, Function.identity()));
		List<AdoptionEffect> items = adoptions.stream().map(a -> toEffect(a, assignment, byId, baseline)).toList();
		return new EffectReport(analysisId, baseline, summarize(items, baseline), items);
	}

	/**
	 * "이 게시물에 썼어요"(used=true): 이 분석의 내 게시물이어야 하고, 다른 채택에 확인된 게시물이 아니어야 한다.
	 * "아니에요"(used=false): 그 게시물을 뗀 목록에 넣고, 확인돼 있던 연결이면 푼다.
	 */
	@Transactional
	public void decide(Long adoptionId, Long analysisId, String mediaId, boolean used) {
		List<RecommendationAdoption> owned = adoptionRepository.findByOwner(ownerOf(analysisId));
		RecommendationAdoption adoption = owned.stream()
			.filter(a -> a.getId().equals(adoptionId))
			.findFirst()
			.orElseThrow(() -> new AdoptionNotFoundException(adoptionId));
		Instant now = Instant.now(clock);
		if (!used) {
			// 확인했던 게시물이 지워져 이번 분석에 없어도 뗄 수 있게, 그 경우는 게시물 확인을 건너뛴다
			if (!mediaId.equals(adoption.getMatchedMediaId())) {
				requireOwnPost(analysisId, mediaId);
			}
			if (mediaId.equals(adoption.getMatchedMediaId())) {
				adoption.clearConfirmation();
			}
			rejectionRepository.insertIgnore(adoptionId, mediaId, now);
			return;
		}
		OwnPost post = requireOwnPost(analysisId, mediaId);
		boolean takenByOther = owned.stream()
			.anyMatch(a -> !a.getId().equals(adoptionId) && a.isConfirmed() && mediaId.equals(a.getMatchedMediaId()));
		if (takenByOther) {
			throw new AdoptionConflictException("이 게시물은 이미 다른 채택 문구에 연결돼 있어요");
		}
		rejectionRepository.deleteById(new AdoptionRejection.Key(adoptionId, mediaId));
		adoption.confirm(mediaId, score(adoption, post), now);
		try {
			adoptionRepository.flush();
		}
		catch (DataIntegrityViolationException e) {
			// 두 요청이 같은 게시물을 동시에 확인한 경우 (유니크 제약)
			throw new AdoptionConflictException("이 게시물은 이미 다른 채택 문구에 연결돼 있어요");
		}
	}

	private OwnPost requireOwnPost(Long analysisId, String mediaId) {
		return ownPostsService.load(analysisId)
			.stream()
			.filter(p -> p.mediaId().equals(mediaId))
			.findFirst()
			.orElseThrow(() -> new InvalidMatchException("이 분석의 내 게시물이 아니에요"));
	}

	static double score(RecommendationAdoption a, OwnPost p) {
		return Math.max(AdoptionMatcher.similarity(a.getHookText(), p.captionHook()),
				AdoptionMatcher.similarity(a.getHookText(), p.captionFirstLine()));
	}

	/**
	 * 확인된 연결을 먼저 두고, 남은 (채택, 게시물) 쌍을 점수 높은 순으로 하나씩 짝짓는다.
	 * 먼저 채택한 문구가 더 잘 맞는 다른 채택의 게시물을 가져가지 않게 한다. 묻는 후보도 남은 게시물에서만, 한 게시물은 한 번만.
	 */
	static Assignment assign(List<RecommendationAdoption> adoptions, List<OwnPost> posts,
			Map<Long, Set<String>> rejected) {
		Map<Long, Link> links = new HashMap<>();
		Set<String> usedPosts = new HashSet<>();
		for (RecommendationAdoption a : adoptions) {
			if (a.isConfirmed()) {
				links.put(a.getId(), new Link(a.getMatchedMediaId(), a.getMatchScore(), "CONFIRMED"));
				usedPosts.add(a.getMatchedMediaId());
			}
		}
		List<Pair> pairs = new ArrayList<>();
		for (RecommendationAdoption a : adoptions) {
			if (a.isConfirmed()) {
				continue;
			}
			Instant earliest = a.getAdoptedAt().minus(POSTED_BEFORE_ADOPTION_TOLERANCE);
			Set<String> no = rejected.getOrDefault(a.getId(), Set.of());
			for (OwnPost p : posts) {
				if (p.postedAt().isBefore(earliest) || usedPosts.contains(p.mediaId()) || no.contains(p.mediaId())) {
					continue;
				}
				double s = score(a, p);
				if (s >= AdoptionMatcher.ASK) {
					pairs.add(new Pair(a.getId(), p.mediaId(), s, a.getAdoptedAt()));
				}
			}
		}
		// 점수 높은 순, 같으면 먼저 채택한 것
		pairs.sort(Comparator.comparingDouble(Pair::score).reversed().thenComparing(Pair::adoptedAt));
		Map<Long, Pair> asks = new HashMap<>();
		for (double threshold : new double[] { AdoptionMatcher.AUTO, AdoptionMatcher.ASK }) {
			for (Pair pair : pairs) {
				if (pair.score() < threshold || links.containsKey(pair.adoptionId())
						|| asks.containsKey(pair.adoptionId()) || usedPosts.contains(pair.mediaId())) {
					continue;
				}
				usedPosts.add(pair.mediaId());
				if (threshold == AdoptionMatcher.AUTO) {
					links.put(pair.adoptionId(), new Link(pair.mediaId(), pair.score(), "AUTO"));
				}
				else {
					asks.put(pair.adoptionId(), pair);
				}
			}
		}
		return new Assignment(links, asks);
	}

	/** 판정한 릴스(게시 7일 이상, 조회수 있음) 중 추천을 쓴 게시물을 뺀 나머지의 중앙값. */
	static Baseline baseline(List<OwnPost> posts, Set<String> exclude) {
		List<OwnPost> judged = posts.stream()
			.filter(p -> p.reels() && p.ageHours() >= OutlierCalculator.MATURE_AGE_HOURS && p.views() != null)
			.filter(p -> !exclude.contains(p.mediaId()))
			.toList();
		return new Baseline(median(judged.stream().map(p -> p.views().doubleValue()).toList()),
				median(judged.stream().map(OwnPost::skipRate).toList()),
				median(judged.stream().map(OwnPost::saveRate).toList()), judged.size());
	}

	private static Double median(List<Double> values) {
		List<Double> present = values.stream().filter(Objects::nonNull).toList();
		return present.isEmpty() ? null : round(OutlierCalculator.median(present));
	}

	private static AdoptionEffect toEffect(RecommendationAdoption a, Assignment assignment, Map<String, OwnPost> byId,
			Baseline baseline) {
		Link link = assignment.links().get(a.getId());
		if (link != null) {
			OwnPost p = byId.get(link.mediaId());
			MatchedPost post = p == null ? MatchedPost.missing(link.mediaId(), link.score())
					: MatchedPost.of(p, baseline, link.score());
			return new AdoptionEffect(a.getId(), a.getRecommendationId(), a.getHookText(), a.getHookType(),
					a.getAdoptedAt(), link.status(), post, null);
		}
		Pair ask = assignment.asks().get(a.getId());
		Candidate candidate = null;
		if (ask != null) {
			OwnPost p = byId.get(ask.mediaId());
			candidate = new Candidate(p.mediaId(), p.permalink(), p.thumbnailUrl(), p.captionFirstLine(), p.postedAt(),
					ask.score());
		}
		return new AdoptionEffect(a.getId(), a.getRecommendationId(), a.getHookText(), a.getHookType(),
				a.getAdoptedAt(), candidate != null ? "ASK" : "NOT_FOUND", null, candidate);
	}

	static EffectSummary summarize(List<AdoptionEffect> items, Baseline baseline) {
		List<MatchedPost> matched = items.stream().map(AdoptionEffect::post).filter(Objects::nonNull).toList();
		List<MatchedPost> compared = matched.stream().filter(p -> p.excludedReason() == null).toList();
		List<Double> skipDiffs = compared.stream().map(MatchedPost::skipRateDiff).filter(Objects::nonNull).toList();
		Double skipDiff = average(skipDiffs);
		Double saveDiff = average(compared.stream().map(MatchedPost::saveRateDiff).toList());
		List<Double> multiples = compared.stream().map(MatchedPost::viewsMultiple).filter(Objects::nonNull).toList();
		Double viewsMultiple = multiples.isEmpty() ? null : round(OutlierCalculator.median(multiples));
		boolean lowSample = skipDiffs.size() < MIN_SAMPLE || baseline.count() < MIN_BASELINE;

		List<String> notes = new ArrayList<>();
		notes.add("추천을 쓴 게시물은 주제·시기·유행도 달라서, 차이를 모두 추천 덕분이라고 단정할 수는 없어요.");
		notes.add("스킵률·조회수는 분석한 시점까지 쌓인 값이에요. 기준값은 추천을 쓰지 않은, 게시 7일이 지난 내 릴스의 중앙값이에요.");
		Map<ExcludedReason, Long> excluded = matched.stream()
			.filter(p -> p.excludedReason() != null)
			.collect(Collectors.groupingBy(MatchedPost::excludedReason, Collectors.counting()));
		if (excluded.containsKey(ExcludedReason.YOUNG)) {
			notes.add("게시 7일이 안 된 게시물 %d개는 참고로만 보여 줘요.".formatted(excluded.get(ExcludedReason.YOUNG)));
		}
		if (excluded.containsKey(ExcludedReason.NOT_REELS)) {
			notes.add("릴스가 아닌 게시물 %d개는 스킵률이 없어 비교에서 뺐어요.".formatted(excluded.get(ExcludedReason.NOT_REELS)));
		}
		if (excluded.containsKey(ExcludedReason.NO_METRICS)) {
			notes.add("이번 분석에서 지표를 받지 못한 게시물 %d개는 비교에서 뺐어요."
				.formatted(excluded.get(ExcludedReason.NO_METRICS)));
		}
		if (!skipDiffs.isEmpty() && skipDiffs.size() < MIN_SAMPLE) {
			notes.add("비교한 게시물이 %d개라 참고용이에요. %d개 이상 쌓이면 더 믿을 만해요.".formatted(skipDiffs.size(), MIN_SAMPLE));
		}
		if (baseline.count() < MIN_BASELINE) {
			notes.add("기준이 될 내 릴스가 %d개뿐이라 기준값도 참고용이에요.".formatted(baseline.count()));
		}
		return new EffectSummary(items.size(), matched.size(), skipDiffs.size(), skipDiff, saveDiff, viewsMultiple,
				lowSample, headline(skipDiffs.size(), skipDiff, baseline), notes);
	}

	private static String headline(int count, Double skipDiff, Baseline baseline) {
		if (count == 0 || skipDiff == null || baseline.medianSkipRate() == null) {
			return null;
		}
		String direction = skipDiff <= 0 ? "낮아요" : "높아요";
		return "추천을 쓴 게시물 %d개의 스킵률이 내 중앙값(%.2f%%)보다 평균 %.2f%%p %s".formatted(count,
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

	record Link(String mediaId, Double score, String status) {
	}

	record Pair(Long adoptionId, String mediaId, double score, Instant adoptedAt) {
	}

	record Assignment(Map<Long, Link> links, Map<Long, Pair> asks) {

		Set<String> linkedMediaIds() {
			return links.values().stream().map(Link::mediaId).collect(Collectors.toSet());
		}
	}

	/** 비교 기준: 추천을 쓰지 않은, 게시 7일이 지난 내 릴스의 중앙값. count는 그 릴스 수. */
	public record Baseline(Double medianViews, Double medianSkipRate, Double medianSaveRate, int count) {
	}

	/** 비교 평균에서 뺀 이유. */
	public enum ExcludedReason {
		/** 게시 7일이 안 됨 */
		YOUNG,
		/** 릴스가 아님 (스킵률·조회수 비교 대상 아님) */
		NOT_REELS,
		/** 이번 분석에서 이 게시물 지표를 받지 못함 (지운 게시물 등) */
		NO_METRICS
	}

	/**
	 * @param status AUTO(자동 연결), CONFIRMED(사용자 확인), ASK(candidate로 "이 게시물에 썼나요?"), NOT_FOUND(아직 비슷한 게시물 없음)
	 */
	public record AdoptionEffect(Long adoptionId, Long recommendationId, String hookText, HookType hookType,
			Instant adoptedAt, String status, MatchedPost post, Candidate candidate) {
	}

	public record Candidate(String mediaId, String permalink, String thumbnailUrl, String captionFirstLine,
			Instant postedAt, double score) {
	}

	/**
	 * @param excludedReason 평균에 넣지 않은 이유. 넣었으면 null
	 * @param skipRateDiff   내 중앙값 대비 %p (음수면 덜 넘겨짐)
	 * @param viewsMultiple  내 조회수 중앙값 대비 배수
	 */
	public record MatchedPost(String mediaId, String permalink, String thumbnailUrl, String captionFirstLine,
			Instant postedAt, Integer ageHours, ExcludedReason excludedReason, Integer views, Double viewsMultiple,
			Double skipRate, Double skipRateDiff, Double saveRate, Double saveRateDiff, Double matchScore) {

		static MatchedPost of(OwnPost p, Baseline b, Double score) {
			ExcludedReason reason = !p.reels() ? ExcludedReason.NOT_REELS
					: p.ageHours() < OutlierCalculator.MATURE_AGE_HOURS ? ExcludedReason.YOUNG : null;
			Double multiple = p.views() == null || b.medianViews() == null || b.medianViews() == 0 ? null
					: round(p.views() / b.medianViews());
			Double skipDiff = p.skipRate() == null || b.medianSkipRate() == null ? null
					: round(p.skipRate() - b.medianSkipRate());
			Double saveRate = round(p.saveRate());
			Double saveDiff = saveRate == null || b.medianSaveRate() == null ? null
					: round(saveRate - b.medianSaveRate());
			return new MatchedPost(p.mediaId(), p.permalink(), p.thumbnailUrl(), p.captionFirstLine(), p.postedAt(),
					p.ageHours(), reason, p.views(), multiple, p.skipRate(), skipDiff, saveRate, saveDiff, score);
		}

		static MatchedPost missing(String mediaId, Double score) {
			return new MatchedPost(mediaId, null, null, null, null, null, ExcludedReason.NO_METRICS, null, null, null,
					null, null, null, score);
		}
	}

	/**
	 * @param comparedCount 평균에 넣은(스킵률이 있는, 게시 7일 지난 릴스) 게시물 수. headline과 lowSample도 이 수로 판단한다
	 * @param headline      예: "추천을 쓴 게시물 3개의 스킵률이 내 중앙값(63.75%)보다 평균 4.58%p 낮아요". 비교할 게시물이 없으면 null
	 */
	public record EffectSummary(int adoptedCount, int matchedCount, int comparedCount, Double avgSkipRateDiff,
			Double avgSaveRateDiff, Double medianViewsMultiple, boolean lowSample, String headline,
			List<String> notes) {
	}

	public record EffectReport(Long analysisId, Baseline baseline, EffectSummary summary,
			List<AdoptionEffect> adoptions) {
	}
}
