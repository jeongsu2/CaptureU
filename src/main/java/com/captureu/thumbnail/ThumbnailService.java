package com.captureu.thumbnail;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.captureu.analysis.MediaQueryService;
import com.captureu.analysis.MediaQueryService.ThumbnailRef;
import com.captureu.analysis.OutlierCalculator.ScoredPost;
import com.captureu.analysis.OutlierService;
import com.captureu.analysis.OutlierService.AccountResult;
import com.captureu.analysis.OutlierService.AnalysisOutliers;
import com.captureu.thumbnail.ThumbnailImageFetcher.Image;
import com.captureu.thumbnail.ThumbnailTagger.Tagged;
import com.captureu.thumbnail.ThumbnailTags.Composition;
import com.captureu.thumbnail.ThumbnailTags.TextSize;
import com.captureu.thumbnail.ThumbnailTags.Tone;

/**
 * 썸네일 특징 추출과 비교 (#11).
 * <p>
 * 계정마다 아웃라이어와 나머지에서 최대 {@value #PER_GROUP}개씩 골라 썸네일을 받아 특징을 뽑는다 (비용 상한).
 * 같은 이미지(SHA-256)는 다시 분석하지 않고, 이미 분석한 게시물은 건너뛴다.
 * "잘된 썸네일은 무엇이 달랐나"를 특징별 비율(아웃라이어 vs 나머지)로 보여 주고, 추천 썸네일 구성안의 근거로 쓴다.
 */
@Service
public class ThumbnailService {

	private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);

	static final int PER_GROUP = 15;
	static final int CONCURRENCY = 4;
	/** 특징이 "잘된 썸네일의 특징"이려면: 양쪽 다 이만큼 분석했고, */
	static final int MIN_TAGGED = 3;
	/** 아웃라이어 쪽 비율이 나머지보다 이만큼(%p) 높고, */
	static final double MIN_DIFF = 20;
	/** 아웃라이어 쪽 비율이 이 이상. */
	static final double MIN_RATE = 40;

	private final OutlierService outlierService;
	private final MediaQueryService mediaQuery;
	private final ThumbnailTagger tagger;
	private final ThumbnailImageFetcher fetcher;
	private final ThumbnailFeatureRepository featureRepository;
	private final MediaThumbnailRepository mediaThumbnailRepository;
	private final Clock clock;

	ThumbnailService(OutlierService outlierService, MediaQueryService mediaQuery, ThumbnailTagger tagger,
			ThumbnailImageFetcher fetcher, ThumbnailFeatureRepository featureRepository,
			MediaThumbnailRepository mediaThumbnailRepository, Clock clock) {
		this.outlierService = outlierService;
		this.mediaQuery = mediaQuery;
		this.tagger = tagger;
		this.fetcher = fetcher;
		this.featureRepository = featureRepository;
		this.mediaThumbnailRepository = mediaThumbnailRepository;
		this.clock = clock;
	}

	public enum TagStatus {
		DONE,
		/** ANTHROPIC_API_KEY가 없어 분석하지 않음 */
		SKIPPED_NO_API_KEY
	}

	/**
	 * @param selected      이번에 고른 게시물 수 (계정마다 아웃라이어·나머지 최대 15개씩)
	 * @param alreadyTagged 이전에 분석해 건너뛴 수
	 * @param tagged        이번에 새로 LLM으로 분석한 수
	 * @param reused        이미지가 같아 이전 결과를 쓴 수
	 * @param expired       썸네일 주소가 만료돼 받지 못한 수 (분석을 새로 실행하면 새 주소를 받는다)
	 * @param noThumbnail   썸네일 주소가 없는 게시물 수
	 * @param unpriced      가격표에 없는 모델이 답해 비용에 넣지 못한 호출 수 (costUsd가 실제보다 적을 수 있음)
	 */
	public record TagResult(TagStatus status, int selected, int alreadyTagged, int tagged, int reused, int expired,
			int noThumbnail, int failed, int unpriced, BigDecimal costUsd, long durationMs) {
	}

	/** 분석 한 건의 썸네일을 분석한다. 이미 분석한 게시물은 건너뛰므로 여러 번 불러도 비용이 다시 들지 않는다. */
	public TagResult tag(Long analysisId) {
		long start = System.nanoTime();
		AnalysisOutliers outliers = outlierService.compute(analysisId);
		List<String> selected = accounts(outliers).stream().flatMap(a -> select(a).stream()).distinct().toList();
		java.util.Set<String> done = currentLinks(selected).keySet();
		List<String> todo = selected.stream().filter(id -> !done.contains(id)).toList();
		int already = selected.size() - todo.size();
		if (!tagger.enabled()) {
			return new TagResult(TagStatus.SKIPPED_NO_API_KEY, selected.size(), already, 0, 0, 0, 0, 0, 0,
					BigDecimal.ZERO, elapsed(start));
		}
		Instant now = Instant.now(clock);
		Map<String, ThumbnailRef> refs = mediaQuery.thumbnails(todo);
		AtomicInteger tagged = new AtomicInteger();
		AtomicInteger reused = new AtomicInteger();
		AtomicInteger failed = new AtomicInteger();
		AtomicInteger unpriced = new AtomicInteger();
		AtomicReference<BigDecimal> cost = new AtomicReference<>(BigDecimal.ZERO);
		int expired = 0;
		int noThumbnail = 0;
		Semaphore permits = new Semaphore(CONCURRENCY);
		// 같은 이미지가 여러 게시물에 쓰였으면(재업로드 등) 이번 실행 안에서도 한 번만 분석한다
		Map<String, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (String mediaId : todo) {
				ThumbnailRef ref = refs.get(mediaId);
				if (ref == null) {
					noThumbnail++;
					continue;
				}
				if (ref.expired(now)) {
					expired++;
					continue;
				}
				executor.submit(() -> {
					permits.acquireUninterruptibly();
					try {
						Outcome outcome = tagOne(mediaId, ref.url(), inFlight);
						(outcome.newlyTagged() ? tagged : reused).incrementAndGet();
						if (outcome.cost() != null) {
							cost.accumulateAndGet(outcome.cost(), BigDecimal::add);
						}
						else if (outcome.called()) {
							unpriced.incrementAndGet();
						}
					}
					catch (RuntimeException e) {
						failed.incrementAndGet();
						log.warn("썸네일 분석 실패 mediaId={} error={}", mediaId, e.getClass().getSimpleName());
					}
					finally {
						permits.release();
					}
				});
			}
		}
		return new TagResult(TagStatus.DONE, selected.size(), already, tagged.get(), reused.get(), expired,
				noThumbnail, failed.get(), unpriced.get(), cost.get(), elapsed(start));
	}

	/** @param called 이번에 LLM을 불렀는지 (비용이 든 호출) */
	private record Outcome(boolean newlyTagged, BigDecimal cost, boolean called) {
	}

	private Outcome tagOne(String mediaId, String url, Map<String, CompletableFuture<Void>> inFlight) {
		Image image = fetcher.fetch(url);
		String hash = sha256(image.bytes());
		Instant now = Instant.now(clock);
		Outcome outcome = new Outcome(false, null, false);
		CompletableFuture<Void> mine = new CompletableFuture<>();
		CompletableFuture<Void> existing = inFlight.putIfAbsent(hash, mine);
		if (existing != null) {
			// 같은 이미지를 다른 게시물이 분석 중이면 끝나길 기다렸다가 그 결과를 쓴다
			existing.join();
			if (!featureRepository.findById(hash).filter(this::current).isPresent()) {
				throw new IllegalStateException("같은 이미지 분석이 실패했습니다");
			}
		}
		else {
			try {
				java.util.Optional<ThumbnailFeature> saved = featureRepository.findById(hash);
				if (saved.isPresent() && !current(saved.get())) {
					// 프롬프트 버전이 바뀌었거나 목 서버 결과라 다시 분석해 덮어쓴다
					Tagged t = tagger.tag(image);
					ThumbnailFeature stale = saved.get();
					stale.retag(t.tags(), t.model(), t.promptVersion(), t.inputTokens(), t.outputTokens(), t.costUsd(),
							now);
					featureRepository.save(stale);
					outcome = new Outcome(true, t.costUsd(), true);
				}
				else if (saved.isEmpty()) {
					Tagged t = tagger.tag(image);
					try {
						featureRepository.save(ThumbnailFeature.of(hash, t.tags(), t.model(), t.promptVersion(),
								t.inputTokens(), t.outputTokens(), t.costUsd(), now));
						outcome = new Outcome(true, t.costUsd(), true);
					}
					catch (DataIntegrityViolationException e) {
						// 다른 요청(동시에 부른 분석)이 같은 이미지를 먼저 저장했을 때만 괜찮다. 그 밖의 무결성 오류는 실패로 본다
						if (!featureRepository.existsById(hash)) {
							throw e;
						}
						outcome = new Outcome(false, t.costUsd(), true);
					}
				}
			}
			finally {
				mine.complete(null);
			}
		}
		java.util.Optional<MediaThumbnail> link = mediaThumbnailRepository.findById(mediaId);
		if (link.isPresent()) {
			if (!link.get().getImageHash().equals(hash)) {
				link.get().relink(hash, now);
				mediaThumbnailRepository.save(link.get());
			}
			return outcome;
		}
		try {
			mediaThumbnailRepository.save(new MediaThumbnail(mediaId, hash, now));
		}
		catch (DataIntegrityViolationException e) {
			// 동시에 같은 게시물을 분석한 요청이 먼저 저장했을 때만 괜찮다
			if (!mediaThumbnailRepository.existsById(mediaId)) {
				throw e;
			}
		}
		return outcome;
	}

	private boolean current(ThumbnailFeature feature) {
		return feature.getPromptVersion().equals(tagger.promptVersion());
	}

	/** 게시물 → 지금 버전으로 분석한 특징. 다른 버전(예전 프롬프트, 목 서버) 결과는 없는 것으로 본다. */
	private Map<String, ThumbnailFeature> currentLinks(List<String> mediaIds) {
		Map<String, String> hashByMedia = mediaThumbnailRepository.findAllById(mediaIds)
			.stream()
			.collect(Collectors.toMap(MediaThumbnail::getMediaId, MediaThumbnail::getImageHash));
		Map<String, ThumbnailFeature> features = featureRepository
			.findAllById(hashByMedia.values().stream().distinct().toList())
			.stream()
			.filter(this::current)
			.collect(Collectors.toMap(ThumbnailFeature::getImageHash, Function.identity()));
		Map<String, ThumbnailFeature> result = new java.util.HashMap<>();
		hashByMedia.forEach((media, hash) -> {
			ThumbnailFeature f = features.get(hash);
			if (f != null) {
				result.put(media, f);
			}
		});
		return result;
	}

	static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static long elapsed(long start) {
		return (System.nanoTime() - start) / 1_000_000;
	}

	private static List<AccountResult> accounts(AnalysisOutliers outliers) {
		List<AccountResult> accounts = new ArrayList<>();
		accounts.add(outliers.own());
		accounts.addAll(outliers.references());
		return accounts;
	}

	/** 아웃라이어는 배수 높은 순, 나머지는 게시물 ID 해시 순(매번 같은 표본)으로 최대 15개씩. */
	static List<String> select(AccountResult account) {
		List<ScoredPost> posts = account.outliers().posts();
		List<String> top = posts.stream()
			.filter(ScoredPost::outlier)
			.sorted(Comparator.comparing(ScoredPost::multiple, Comparator.nullsLast(Comparator.reverseOrder())))
			.limit(PER_GROUP)
			.map(ScoredPost::mediaId)
			.toList();
		List<String> rest = posts.stream()
			.filter(p -> !p.outlier())
			.map(ScoredPost::mediaId)
			.sorted(Comparator.comparing(ThumbnailService::sampleKey))
			.limit(PER_GROUP)
			.toList();
		List<String> all = new ArrayList<>(top);
		all.addAll(rest);
		return all;
	}

	private static String sampleKey(String mediaId) {
		return sha256(mediaId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	// ---- 비교 ----

	/** 비교하는 특징. 각각 "이 특징이 있는 썸네일 비율"을 아웃라이어와 나머지로 나눠 본다. */
	public enum Feature {
		FACE_CLOSE_UP("얼굴 클로즈업", "PERSON", f -> f.isFaceCloseUp()),
		LARGE_TEXT("큰 제목 글씨", "TEXT", f -> f.getTextSize() == TextSize.LARGE),
		ANY_TEXT("글씨 있음", "TEXT", f -> f.getTextSize() != TextSize.NONE),
		NO_PERSON("사람 없음", "PERSON", f -> f.getPersonCount() == 0),
		TONE_BRIGHT("밝은 색감", "TONE", f -> f.getTone() == Tone.BRIGHT),
		TONE_DARK("어두운 색감", "TONE", f -> f.getTone() == Tone.DARK),
		TONE_PASTEL("파스텔 색감", "TONE", f -> f.getTone() == Tone.PASTEL),
		TONE_VIVID("강렬한 색감", "TONE", f -> f.getTone() == Tone.VIVID),
		CENTER_SUBJECT("가운데 인물·제품", "COMPOSITION", f -> f.getComposition() == Composition.CENTER_SUBJECT),
		FULL_BODY("전신", "COMPOSITION", f -> f.getComposition() == Composition.FULL_BODY),
		FLAT_LAY("위에서 본 제품 배치", "COMPOSITION", f -> f.getComposition() == Composition.FLAT_LAY),
		SPLIT("전후 비교·분할", "COMPOSITION", f -> f.getComposition() == Composition.SPLIT),
		TEXT_CARD("글자 카드", "COMPOSITION", f -> f.getComposition() == Composition.TEXT_CARD);

		final String label;
		/** 같은 그룹의 특징은 서로 겹치거나 반대라(얼굴 클로즈업 vs 사람 없음, 색감 여러 개) 근거로는 하나만 쓴다. */
		final String group;
		final Predicate<ThumbnailFeature> test;

		Feature(String label, String group, Predicate<ThumbnailFeature> test) {
			this.label = label;
			this.group = group;
			this.test = test;
		}

		public String label() {
			return label;
		}
	}

	/**
	 * @param outlierRate 분석한 아웃라이어 썸네일 중 이 특징이 있는 비율(%)
	 * @param otherRate   분석한 나머지 썸네일 중 비율(%)
	 * @param signal      잘된 썸네일의 특징으로 볼 만큼 차이가 큼
	 */
	public record FeatureStat(Feature feature, String label, double outlierRate, double otherRate, double diff,
			boolean signal) {
	}

	public record AccountThumbnails(String username, boolean mine, int outlierTagged, int otherTagged,
			List<FeatureStat> features) {
	}

	public enum Scope {
		/** 참고 계정 2개 이상에서 잘된 썸네일의 특징 */
		COMMON,
		/** 내 계정에서 잘된 썸네일의 특징 */
		OWN,
		/** 참고 계정 한 곳의 특징 */
		SINGLE
	}

	/**
	 * 추천 썸네일 구성안의 근거.
	 *
	 * @param evidence 예: "참고 계정 3개 중 2개(@a 잘된 썸네일 70% vs 나머지 30%, ...)에서 얼굴 클로즈업"
	 */
	public record ThumbnailSignal(Feature feature, String label, Scope scope, int referenceAccounts, boolean own,
			String evidence) {
	}

	public record ThumbnailComparison(Long analysisId, List<AccountThumbnails> accounts,
			List<ThumbnailSignal> signals) {
	}

	public ThumbnailComparison compare(Long analysisId) {
		AnalysisOutliers outliers = outlierService.compute(analysisId);
		List<AccountResult> accounts = accounts(outliers);
		List<String> ids = accounts.stream()
			.flatMap(a -> a.outliers().posts().stream().map(ScoredPost::mediaId))
			.distinct()
			.toList();
		Map<String, ThumbnailFeature> features = currentLinks(ids);

		List<AccountThumbnails> result = new ArrayList<>();
		for (AccountResult a : accounts) {
			List<ThumbnailFeature> top = new ArrayList<>();
			List<ThumbnailFeature> rest = new ArrayList<>();
			for (ScoredPost p : a.outliers().posts()) {
				ThumbnailFeature f = features.get(p.mediaId());
				if (f != null) {
					(p.outlier() ? top : rest).add(f);
				}
			}
			result.add(new AccountThumbnails(a.username(), a.mine(), top.size(), rest.size(), stats(top, rest)));
		}
		int referenceCount = outliers.references().size();
		return new ThumbnailComparison(analysisId, result, signals(result, referenceCount));
	}

	/** 추천에 쓸 근거만. 분석한 썸네일이 없으면 빈 목록. */
	public List<ThumbnailSignal> signals(Long analysisId) {
		return compare(analysisId).signals();
	}

	static List<FeatureStat> stats(List<ThumbnailFeature> top, List<ThumbnailFeature> rest) {
		List<FeatureStat> stats = new ArrayList<>();
		for (Feature feature : Feature.values()) {
			double topRate = rate(top, feature);
			double restRate = rate(rest, feature);
			double diff = Math.round((topRate - restRate) * 10) / 10.0;
			boolean signal = top.size() >= MIN_TAGGED && rest.size() >= MIN_TAGGED && diff >= MIN_DIFF
					&& topRate >= MIN_RATE;
			stats.add(new FeatureStat(feature, feature.label(), topRate, restRate, diff, signal));
		}
		stats.sort(Comparator.comparingDouble(FeatureStat::diff).reversed());
		return stats;
	}

	private static double rate(List<ThumbnailFeature> list, Feature feature) {
		if (list.isEmpty()) {
			return 0;
		}
		long count = list.stream().filter(feature.test).count();
		return Math.round(1000.0 * count / list.size()) / 10.0;
	}

	/** 해시 순서가 아니라 공통 → 내 계정 → 한 계정 순, 같은 범위면 근거 계정이 많은 순. */
	static List<ThumbnailSignal> signals(List<AccountThumbnails> accounts, int referenceCount) {
		Map<Feature, List<String>> refEvidence = new LinkedHashMap<>();
		Map<Feature, String> ownEvidence = new LinkedHashMap<>();
		for (AccountThumbnails a : accounts) {
			for (FeatureStat s : a.features()) {
				if (!s.signal()) {
					continue;
				}
				String text = "@%s 잘된 썸네일 %.0f%% vs 나머지 %.0f%%".formatted(a.username(), s.outlierRate(),
						s.otherRate());
				if (a.mine()) {
					ownEvidence.put(s.feature(), "내 계정(잘된 썸네일 %.0f%% vs 나머지 %.0f%%)".formatted(s.outlierRate(),
							s.otherRate()));
				}
				else {
					refEvidence.computeIfAbsent(s.feature(), k -> new ArrayList<>()).add(text);
				}
			}
		}
		List<ThumbnailSignal> signals = new ArrayList<>();
		for (Feature f : Feature.values()) {
			List<String> refs = refEvidence.getOrDefault(f, List.of());
			String own = ownEvidence.get(f);
			if (refs.isEmpty() && own == null) {
				continue;
			}
			Scope scope = refs.size() >= 2 ? Scope.COMMON : own != null ? Scope.OWN : Scope.SINGLE;
			List<String> parts = new ArrayList<>();
			if (!refs.isEmpty()) {
				parts.add("참고 계정 %d개 중 %d개(%s)".formatted(Math.max(referenceCount, refs.size()), refs.size(),
						String.join(", ", refs)));
			}
			if (own != null) {
				parts.add(own);
			}
			signals.add(new ThumbnailSignal(f, f.label(), scope, refs.size(), own != null,
					String.join("와 ", parts) + "에서 잘된 썸네일의 특징: " + f.label()));
		}
		// 공통 → 내 계정 → 한 계정, 같은 범위면 근거 계정이 많은 것, 내 계정에서도 잘된 것 먼저
		signals.sort(Comparator.comparing(ThumbnailSignal::scope)
			.thenComparing(ThumbnailSignal::referenceAccounts, Comparator.reverseOrder())
			.thenComparing(s -> !s.own()));
		// 같은 그룹에서는 우선순위가 가장 높은 하나만 남긴다 (서로 반대되는 지시가 함께 나가지 않게)
		java.util.Set<String> groups = new java.util.HashSet<>();
		return signals.stream().filter(s -> groups.add(s.feature().group)).toList();
	}
}
