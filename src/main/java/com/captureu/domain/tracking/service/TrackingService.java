package com.captureu.domain.tracking.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.captureu.domain.auth.dto.CurrentAccount;
import com.captureu.domain.auth.service.CurrentAccountResolver;
import com.captureu.domain.instagram.client.InstagramAccountReader;
import com.captureu.domain.instagram.client.ReadContext;
import com.captureu.domain.instagram.dto.MediaInsights;
import com.captureu.domain.instagram.dto.MediaItem;
import com.captureu.domain.tracking.dto.response.TrackedPostResponse;
import com.captureu.domain.tracking.dto.response.TrackingResponse;
import com.captureu.domain.tracking.entity.MediaCheckpoint;
import com.captureu.domain.tracking.entity.Tracking;
import com.captureu.domain.tracking.repository.MediaCheckpointRepository;
import com.captureu.domain.tracking.repository.TrackingRepository;
import com.captureu.domain.tracking.service.Checkpoints.Comparison;

/**
 * '더 이어서 하기' (#5). 추적을 켠 계정만 최근 릴스의 나이별 누적 지표를 쌓고,
 * 같은 나이의 과거 게시물 중앙값과 비교해 초반 급상승을 알려 준다.
 * 외부 호출이 끼어 있어 트랜잭션은 걸지 않는다 (저장은 행 하나씩, 중복은 유니크 키가 막는다).
 */
@Service
public class TrackingService {

	private static final Logger log = LoggerFactory.getLogger(TrackingService.class);

	/** 한 번에 볼 최근 게시물 수. 7일 안에 이보다 많이 올리는 경우는 드물다. */
	static final int RECENT_LIMIT = 25;
	/** 화면에 보여 줄 최근 게시물 수. */
	static final int SHOW_LIMIT = 10;

	private final TrackingRepository trackingRepository;
	private final MediaCheckpointRepository checkpointRepository;
	private final InstagramAccountReader reader;
	private final CurrentAccountResolver accounts;
	private final Clock clock;

	public TrackingService(TrackingRepository trackingRepository, MediaCheckpointRepository checkpointRepository,
			InstagramAccountReader reader, CurrentAccountResolver accounts, Clock clock) {
		this.trackingRepository = trackingRepository;
		this.checkpointRepository = checkpointRepository;
		this.reader = reader;
		this.accounts = accounts;
		this.clock = clock;
	}

	public TrackingResponse change(boolean enabled) {
		String owner = owner();
		Instant now = clock.instant();
		Tracking tracking = trackingRepository.findById(owner).map(t -> {
			t.change(enabled, now);
			return t;
		}).orElseGet(() -> enabled ? Tracking.start(owner, now) : null);
		if (tracking != null) {
			trackingRepository.save(tracking);
		}
		return status();
	}

	public TrackingResponse status() {
		String owner = owner();
		Optional<Tracking> tracking = trackingRepository.findById(owner);
		Map<String, List<MediaCheckpoint>> byMedia = checkpointRepository.findByIgUserId(owner)
			.stream()
			.collect(Collectors.groupingBy(MediaCheckpoint::getMediaId));
		List<TrackedPostResponse> posts = byMedia.values()
			.stream()
			.map(list -> list.stream().max(Comparator.comparingInt(MediaCheckpoint::getCheckpointHours)).orElseThrow())
			.sorted(Comparator.comparing(MediaCheckpoint::getPostedAt).reversed())
			.limit(SHOW_LIMIT)
			.map(latest -> compare(latest, byMedia))
			.toList();
		return new TrackingResponse(tracking.map(Tracking::isEnabled).orElse(false),
				tracking.map(Tracking::getStartedAt).orElse(null), posts);
	}

	/** 같은 나이를 잰 다른 게시물 중 이 게시물보다 먼저 올린 것과 비교한다. */
	static TrackedPostResponse compare(MediaCheckpoint latest, Map<String, List<MediaCheckpoint>> byMedia) {
		List<Integer> past = byMedia.values()
			.stream()
			.flatMap(List::stream)
			.filter(c -> c.getCheckpointHours() == latest.getCheckpointHours())
			.filter(c -> !c.getMediaId().equals(latest.getMediaId()))
			.filter(c -> c.getPostedAt().isBefore(latest.getPostedAt()))
			.map(MediaCheckpoint::getViews)
			.toList();
		Comparison cmp = Checkpoints.compare(latest.getViews(), past);
		return new TrackedPostResponse(latest.getMediaId(), latest.getPermalink(), latest.getCaptionFirstLine(),
				latest.getPostedAt(), latest.getCheckpointHours(), latest.getViews(), latest.getSkipRate(),
				cmp.baseline(), cmp.baselineCount(), cmp.multiple(), cmp.surge());
	}

	/** @param saved 이번에 저장한 시점 수 */
	public record CollectResult(boolean tracking, int recent, int saved, int failed, int apiCalls) {
	}

	/**
	 * 스케줄러가 부른다. 추적을 켠 계정마다 그 계정의 토큰으로, 최근 릴스 중 지금 잴 나이가 된 것만 인사이트를 받아 저장한다.
	 * 한 계정이 실패(토큰 만료 등)해도 다른 계정은 계속 잰다.
	 */
	public List<CollectResult> collectAll() {
		List<CollectResult> results = new java.util.ArrayList<>();
		for (Tracking t : trackingRepository.findByEnabledTrue()) {
			Optional<CurrentAccount> account = accounts.forOwner(t.getIgUserId());
			if (account.isEmpty()) {
				log.warn("추적 중인데 토큰이 없어 건너뜀 igUserId={}", t.getIgUserId());
				continue;
			}
			try {
				results.add(collectDue(account.get()));
			}
			catch (RuntimeException e) {
				log.warn("추적 수집 실패 igUserId={} error={}", t.getIgUserId(), e.getClass().getSimpleName());
			}
		}
		return results;
	}

	/** 지금 계정(로그인 또는 .env) 하나만 잰다. */
	public CollectResult collectDue() {
		return accounts.find().map(this::collectDue).orElseGet(() -> new CollectResult(false, 0, 0, 0, 0));
	}

	CollectResult collectDue(CurrentAccount account) {
		String owner = account.igUserId();
		if (!trackingRepository.findById(owner).map(Tracking::isEnabled).orElse(false)) {
			return new CollectResult(false, 0, 0, 0, 0);
		}
		Instant now = clock.instant();
		ReadContext ctx = new ReadContext(account.accessToken());
		List<MediaItem> recent = reader.readRecentMedia(owner, RECENT_LIMIT, ctx)
			.stream()
			.filter(MediaItem::isReels)
			.filter(m -> m.postedAt() != null && !Checkpoints.finished(m.postedAt(), now))
			.toList();
		Map<String, Set<Integer>> done = new HashMap<>();
		checkpointRepository.findByMediaIdIn(recent.stream().map(MediaItem::id).toList())
			.forEach(c -> done.computeIfAbsent(c.getMediaId(), k -> new HashSet<>()).add(c.getCheckpointHours()));
		int saved = 0;
		int failed = 0;
		for (MediaItem media : recent) {
			Optional<Integer> due = Checkpoints.due(media.postedAt(), now, done.getOrDefault(media.id(), Set.of()));
			if (due.isEmpty()) {
				continue;
			}
			MediaInsights insights = reader.readInsights(media, ctx);
			// 비교 기준인 조회수가 없으면 저장하지 않고 다음 주기(허용 시간 안)에 다시 잰다
			if (insights.isFailed() || insights.views() == null) {
				failed++;
				log.warn("추적 인사이트 실패 mediaId={} hours={} error={}", media.id(), due.get(), insights.error());
				continue;
			}
			try {
				checkpointRepository.save(MediaCheckpoint.of(owner, media, due.get(), insights, clock.instant()));
				saved++;
			}
			catch (DataIntegrityViolationException e) {
				// 다른 실행이 같은 게시물·나이를 먼저 저장했다
				log.debug("이미 저장된 시점 mediaId={} hours={}", media.id(), due.get());
			}
		}
		return new CollectResult(true, recent.size(), saved, failed, ctx.callCount());
	}

	private String owner() {
		return accounts.current().igUserId();
	}
}
