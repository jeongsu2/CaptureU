package com.captureu.reference;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.captureu.instagram.account.ReferenceAccountReader.ReferenceAccountData;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 참고 계정 수집 결과를 Redis에 사용자끼리 공유해서 캐싱한다 (#9).
 * <ul>
 * <li>참고 계정의 공개 정보는 누가 조회하든 같으므로 키에 사용자를 넣지 않는다. 같은 인기 계정을 여러 사용자가 넣으면 한 번만 부른다.
 * 목적은 응답 시간이 아니라 외부 호출 수(호출 한도) 절약이다 (참고 계정은 내 계정과 동시에 모으므로 시간은 이미 숨어 있다).</li>
 * <li>TTL(기본 6시간) 근거: 아웃라이어 판정은 게시 7일 이상 게시물만 쓰고, 그런 게시물의 조회수는 몇 시간 사이에 크게 변하지 않는다.
 * 썸네일 URL은 약 4일 뒤 만료되므로 그보다 훨씬 짧게 둔다. 받은 시각(fetchedAt)을 함께 저장해 스냅샷 시각으로 쓴다.</li>
 * <li>같은 계정을 동시에 요청하면 한 번만 부르고 나머지는 그 결과를 기다린다 (서버 한 대 기준).
 * <b>성공한 결과만 나눈다.</b> 먼저 부른 쪽이 실패하면(그 분석이 멈췄거나 취소된 경우 등) 기다리던 쪽은 직접 부른다.</li>
 * <li>Redis가 안 되면 캐시 없이 직접 부른다. 캐시는 분석을 실패시키지 않는다.</li>
 * <li>일부만 받은 결과(계정 ID 없음, 기대보다 게시물이 적음)는 6시간 동안 모두에게 퍼지지 않도록 저장하지 않는다.</li>
 * </ul>
 */
@Component
public class ReferenceAccountCache {

	private static final Logger log = LoggerFactory.getLogger(ReferenceAccountCache.class);

	/** 캐시에 저장하는 레코드 모양이 바뀌면 이 버전을 올린다 (예전 형식은 TTL이 지나 사라진다). */
	static final String KEY_PREFIX = "captureu:reference:v2:";

	/** 먼저 부른 쪽을 기다리는 최대 시간. 넘으면 직접 부른다. */
	private static final long WAIT_SECONDS = 30;

	private final StringRedisTemplate redis;
	private final Clock clock;
	private final Duration ttl;
	private final Counter redisHits;
	private final Counter sharedHits;
	private final Counter misses;
	private final Counter errors;
	private final Map<String, CompletableFuture<Cached>> inFlight = new ConcurrentHashMap<>();

	/** 웹 응답용 ObjectMapper 설정이 바뀌어도 저장 형식이 흔들리지 않도록 캐시 전용으로 쓴다. */
	private final ObjectMapper objectMapper = JsonMapper.builder()
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.build();

	ReferenceAccountCache(StringRedisTemplate redis, MeterRegistry meterRegistry, Clock clock,
			@Value("${captureu.reference.cache-ttl:6h}") Duration ttl) {
		this.redis = redis;
		this.clock = clock;
		this.ttl = ttl;
		this.redisHits = meterRegistry.counter("captureu.reference.cache", "result", "hit");
		this.sharedHits = meterRegistry.counter("captureu.reference.cache", "result", "shared");
		this.misses = meterRegistry.counter("captureu.reference.cache", "result", "miss");
		this.errors = meterRegistry.counter("captureu.reference.cache", "result", "error");
	}

	/**
	 * 캐시에 있으면 꺼내고, 없으면 loader로 받아 저장한다.
	 *
	 * @param maxMedia      받는 게시물 수가 다르면 다른 결과이므로 키에 넣는다
	 * @param expectedIgId  등록할 때 확인한 계정 ID. 캐시의 ID와 다르면(아이디를 다른 계정이 가져감) 캐시를 쓰지 않는다
	 */
	public Lookup getOrLoad(String username, int maxMedia, String expectedIgId, Supplier<ReferenceAccountData> loader) {
		String key = KEY_PREFIX + username + ":" + maxMedia;
		Cached cached = read(key, expectedIgId);
		if (cached != null) {
			redisHits.increment();
			return new Lookup(cached.data(), cached.fetchedAt(), Source.REDIS);
		}

		CompletableFuture<Cached> mine = new CompletableFuture<>();
		CompletableFuture<Cached> existing = inFlight.putIfAbsent(key, mine);
		if (existing != null) {
			Cached shared = awaitSuccess(existing);
			if (shared != null) {
				sharedHits.increment();
				return new Lookup(shared.data(), shared.fetchedAt(), Source.SHARED);
			}
			// 먼저 부른 쪽이 실패했거나 너무 오래 걸리면, 그 실패를 물려받지 않고 직접 부른다
			return load(key, loader, null);
		}
		try {
			// 앞선 요청이 방금 저장하고 빠졌을 수 있으니 한 번 더 확인한다
			Cached justWritten = read(key, expectedIgId);
			if (justWritten != null) {
				mine.complete(justWritten);
				redisHits.increment();
				return new Lookup(justWritten.data(), justWritten.fetchedAt(), Source.REDIS);
			}
			return load(key, loader, mine);
		}
		finally {
			inFlight.remove(key, mine);
		}
	}

	/** 진행 중인 로드를 기다리고 있는 요청 수. 테스트에서 기다리는 쪽이 합류한 뒤에 로드를 끝내려고 쓴다. */
	int waitingFor(String username, int maxMedia) {
		CompletableFuture<Cached> future = inFlight.get(KEY_PREFIX + username + ":" + maxMedia);
		return future == null ? 0 : future.getNumberOfDependents();
	}

	private Lookup load(String key, Supplier<ReferenceAccountData> loader, CompletableFuture<Cached> waiters) {
		misses.increment();
		Cached loaded;
		try {
			loaded = new Cached(Instant.now(clock), loader.get());
		}
		catch (Throwable e) {
			if (waiters != null) {
				waiters.completeExceptionally(e);
			}
			throw e;
		}
		// 기다리는 쪽을 Redis 저장보다 먼저 풀어 준다
		if (waiters != null) {
			waiters.complete(loaded);
		}
		if (isComplete(loaded.data())) {
			write(key, loaded);
		}
		else {
			log.info("참고 계정 @{} 결과가 일부만 와서 캐시에 저장하지 않음", loaded.data().profile().username());
		}
		return new Lookup(loaded.data(), loaded.fetchedAt(), Source.LOADED);
	}

	/** 계정 ID가 있고, 기대한 만큼 게시물이 왔을 때만 모두에게 나눠도 된다. */
	static boolean isComplete(ReferenceAccountData data) {
		if (data.profile().igId() == null) {
			return false;
		}
		Integer reported = data.profile().reportedMediaCount();
		return reported == null || reported == 0 || !data.media().isEmpty();
	}

	private Cached read(String key, String expectedIgId) {
		try {
			String json = redis.opsForValue().get(key);
			if (json == null) {
				return null;
			}
			Cached cached = objectMapper.readValue(json, Cached.class);
			if (expectedIgId != null && !Objects.equals(expectedIgId, cached.data().profile().igId())) {
				return null;
			}
			return cached;
		}
		catch (RuntimeException e) {
			errors.increment();
			log.warn("참고 계정 캐시를 읽지 못해 직접 조회함: {}", e.getMessage());
			return null;
		}
	}

	private void write(String key, Cached cached) {
		try {
			redis.opsForValue().set(key, objectMapper.writeValueAsString(cached), ttl);
		}
		catch (RuntimeException e) {
			errors.increment();
			log.warn("참고 계정 캐시를 저장하지 못함: {}", e.getMessage());
		}
	}

	/** 성공하면 결과, 실패·시간 초과면 null. 다른 분석의 실패를 이 분석으로 옮기지 않는다. */
	private static Cached awaitSuccess(CompletableFuture<Cached> future) {
		try {
			return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("참고 계정 캐시를 기다리는 중 스레드가 중단됨", e);
		}
		catch (ExecutionException | TimeoutException e) {
			return null;
		}
	}

	/** Redis에 저장하는 모양. 받은 시각을 함께 둔다. */
	record Cached(Instant fetchedAt, ReferenceAccountData data) {
	}

	/** 결과를 어디서 얻었는지. REDIS·SHARED는 외부 호출이 없다. */
	public enum Source {
		/** Redis 캐시에서 꺼냄 */
		REDIS,
		/** 같은 계정을 동시에 받던 다른 요청의 결과를 같이 씀 */
		SHARED,
		/** 직접 불러옴 */
		LOADED
	}

	/**
	 * @param fetchedAt 실제로 인스타에서 받은 시각. 캐시에서 꺼냈으면 최대 TTL만큼 과거다
	 */
	public record Lookup(ReferenceAccountData data, Instant fetchedAt, Source source) {

		public boolean fromCache() {
			return source != Source.LOADED;
		}
	}
}
