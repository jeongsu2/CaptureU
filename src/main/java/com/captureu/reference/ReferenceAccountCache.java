package com.captureu.reference;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.captureu.instagram.account.ReferenceAccountReader.ReferenceAccountData;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * 참고 계정 수집 결과를 Redis에 사용자끼리 공유해서 캐싱한다 (#9).
 * <ul>
 * <li>참고 계정의 공개 정보는 누가 조회하든 같으므로 키에 사용자를 넣지 않는다. 같은 인기 계정을 여러 사용자가 넣으면 한 번만 부른다.</li>
 * <li>TTL(기본 6시간) 근거: 아웃라이어 판정은 게시 7일 이상 게시물만 쓰고, 그런 게시물의 조회수는 몇 시간 사이에 크게 변하지 않는다.
 * 썸네일 URL은 약 4일 뒤 만료되므로 그보다 훨씬 짧게 둔다.</li>
 * <li>같은 계정을 동시에 요청하면 한 번만 부르고 나머지는 그 결과를 기다린다 (서버 한 대 기준. 여러 대가 되면 Redis 락으로 바꾼다).</li>
 * <li>Redis가 안 되면 캐시 없이 직접 부른다. 캐시는 성능용이라 분석을 실패시키지 않는다.</li>
 * </ul>
 */
@Component
public class ReferenceAccountCache {

	private static final Logger log = LoggerFactory.getLogger(ReferenceAccountCache.class);
	static final String KEY_PREFIX = "captureu:reference:v1:";

	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final Duration ttl;
	private final Counter hits;
	private final Counter misses;
	private final Counter errors;
	private final Map<String, CompletableFuture<ReferenceAccountData>> inFlight = new ConcurrentHashMap<>();

	ReferenceAccountCache(StringRedisTemplate redis, ObjectMapper objectMapper, MeterRegistry meterRegistry,
			@Value("${captureu.reference.cache-ttl:6h}") Duration ttl) {
		this.redis = redis;
		this.objectMapper = objectMapper;
		this.ttl = ttl;
		this.hits = meterRegistry.counter("captureu.reference.cache", "result", "hit");
		this.misses = meterRegistry.counter("captureu.reference.cache", "result", "miss");
		this.errors = meterRegistry.counter("captureu.reference.cache", "result", "error");
	}

	/**
	 * 캐시에 있으면 꺼내고, 없으면 loader로 받아 저장한다.
	 *
	 * @param maxMedia 받는 게시물 수가 다르면 다른 결과이므로 키에 넣는다
	 */
	public Lookup getOrLoad(String username, int maxMedia, Supplier<ReferenceAccountData> loader) {
		String key = KEY_PREFIX + username + ":" + maxMedia;
		ReferenceAccountData cached = read(key);
		if (cached != null) {
			hits.increment();
			return new Lookup(cached, true);
		}
		misses.increment();

		CompletableFuture<ReferenceAccountData> mine = new CompletableFuture<>();
		CompletableFuture<ReferenceAccountData> existing = inFlight.putIfAbsent(key, mine);
		if (existing != null) {
			// 같은 계정을 이미 다른 요청이 받는 중이면 그 결과를 같이 쓴다
			return new Lookup(join(existing), true);
		}
		try {
			ReferenceAccountData loaded = loader.get();
			write(key, loaded);
			mine.complete(loaded);
			return new Lookup(loaded, false);
		}
		catch (RuntimeException | Error e) {
			mine.completeExceptionally(e);
			throw e;
		}
		finally {
			inFlight.remove(key, mine);
		}
	}

	private ReferenceAccountData read(String key) {
		try {
			String json = redis.opsForValue().get(key);
			return json == null ? null : objectMapper.readValue(json, ReferenceAccountData.class);
		}
		catch (RuntimeException e) {
			errors.increment();
			log.warn("참고 계정 캐시를 읽지 못해 직접 조회함: {}", e.getMessage());
			return null;
		}
	}

	private void write(String key, ReferenceAccountData data) {
		try {
			redis.opsForValue().set(key, objectMapper.writeValueAsString(data), ttl);
		}
		catch (RuntimeException e) {
			errors.increment();
			log.warn("참고 계정 캐시를 저장하지 못함: {}", e.getMessage());
		}
	}

	private static ReferenceAccountData join(CompletableFuture<ReferenceAccountData> future) {
		try {
			return future.get();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("참고 계정 캐시를 기다리는 중 스레드가 중단됨", e);
		}
		catch (ExecutionException e) {
			if (e.getCause() instanceof RuntimeException r) {
				throw r;
			}
			if (e.getCause() instanceof Error err) {
				throw err;
			}
			throw new IllegalStateException(e.getCause());
		}
	}

	/** @param fromCache 외부 호출 없이 얻었는지 (Redis에서 꺼냈거나, 동시에 받던 다른 요청의 결과를 썼거나) */
	public record Lookup(ReferenceAccountData data, boolean fromCache) {
	}
}
