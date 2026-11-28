package com.captureu.domain.reference.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.captureu.domain.instagram.client.ReferenceAccountReader.ReferenceAccountData;
import com.captureu.domain.instagram.client.ReferenceAccountReader.ReferenceMedia;
import com.captureu.domain.instagram.client.ReferenceAccountReader.ReferenceProfile;
import com.captureu.domain.instagram.dto.MediaItem;
import com.captureu.domain.reference.service.ReferenceAccountCache.Lookup;
import com.captureu.domain.reference.service.ReferenceAccountCache.Source;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ReferenceAccountCacheTest {

	private static final Instant NOW = Instant.parse("2026-11-28T00:00:00Z");
	private static final ReferenceAccountData DATA = new ReferenceAccountData(
			new ReferenceProfile("900", "ref.a", 1, 1),
			List.of(new ReferenceMedia(new MediaItem("r1", "VIDEO", "REELS", Instant.parse("2026-11-01T00:00:00Z"),
					"첫 줄 😀", "https://t", null, "https://p", null, 3), 1234)));

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> ops = mock(ValueOperations.class);
	/** Redis 대신 쓰는 메모리 저장소 */
	private final Map<String, String> store = new ConcurrentHashMap<>();
	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
	private final ReferenceAccountCache cache = new ReferenceAccountCache(redis, meters,
			Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(6));

	@BeforeEach
	void setUp() {
		given(redis.opsForValue()).willReturn(ops);
		given(ops.get(anyString())).willAnswer(inv -> store.get(inv.<String>getArgument(0)));
		org.mockito.BDDMockito.willAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1)))
			.given(ops)
			.set(anyString(), anyString(), any(Duration.class));
	}

	@Test
	void 처음엔_불러서_저장하고_다음엔_받은_시각과_함께_꺼낸다() {
		Lookup first = cache.getOrLoad("ref.a", 100, "900", () -> DATA);
		Lookup second = cache.getOrLoad("ref.a", 100, "900", () -> {
			throw new AssertionError("캐시에 있으면 부르지 않아야 함");
		});

		assertThat(first.source()).isEqualTo(Source.LOADED);
		assertThat(second.source()).isEqualTo(Source.REDIS);
		// JSON으로 저장했다가 꺼내도 내용(이모지, 시각 포함)이 같다
		assertThat(second.data()).isEqualTo(DATA);
		assertThat(second.fetchedAt()).isEqualTo(NOW);
		assertThat(store.values()).singleElement().satisfies(json -> assertThat(json).doesNotContain("\"reels\""));
	}

	@Test
	void 아이디를_다른_계정이_가져갔으면_캐시를_쓰지_않는다() {
		cache.getOrLoad("ref.a", 100, "900", () -> DATA);
		AtomicInteger loads = new AtomicInteger();

		Lookup lookup = cache.getOrLoad("ref.a", 100, "777", () -> {
			loads.incrementAndGet();
			return DATA;
		});

		assertThat(loads).hasValue(1);
		assertThat(lookup.source()).isEqualTo(Source.LOADED);
	}

	@Test
	void 일부만_받은_결과는_저장하지_않는다() {
		ReferenceAccountData partial = new ReferenceAccountData(new ReferenceProfile(null, "ref.a", null, null),
				List.of());

		cache.getOrLoad("ref.a", 100, null, () -> partial);

		verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
		assertThat(ReferenceAccountCache.isComplete(partial)).isFalse();
		assertThat(ReferenceAccountCache.isComplete(
				new ReferenceAccountData(new ReferenceProfile("900", "ref.a", 1, 50), List.of()))).isFalse();
		assertThat(ReferenceAccountCache.isComplete(DATA)).isTrue();
	}

	@Test
	void Redis가_안_되면_직접_불러서_분석을_계속한다() {
		given(ops.get(anyString())).willThrow(new RedisConnectionFailureException("down"));
		AtomicInteger loads = new AtomicInteger();

		Lookup lookup = cache.getOrLoad("ref.a", 100, "900", () -> {
			loads.incrementAndGet();
			return DATA;
		});

		assertThat(lookup.fromCache()).isFalse();
		assertThat(lookup.data()).isEqualTo(DATA);
		assertThat(loads).hasValue(1);
		assertThat(meters.counter("captureu.reference.cache", "result", "error").count()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void 같은_계정을_동시에_요청하면_한_번만_부르고_결과를_나눈다() throws Exception {
		AtomicInteger loads = new AtomicInteger();
		CountDownLatch loading = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		Thread first = Thread.ofVirtual().start(() -> cache.getOrLoad("ref.a", 100, "900", () -> {
			loads.incrementAndGet();
			loading.countDown();
			await(release);
			return DATA;
		}));
		assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
		Lookup[] second = new Lookup[1];
		Thread waiter = Thread.ofVirtual().start(() -> second[0] = cache.getOrLoad("ref.a", 100, "900", () -> {
			loads.incrementAndGet();
			return DATA;
		}));
		// 기다리는 쪽이 진행 중인 로드에 합류한 뒤에 끝내야 한다. 먼저 끝내면 Redis에서 읽어 SHARED가 아니게 된다
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (cache.waitingFor("ref.a", 100) == 0 && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertThat(cache.waitingFor("ref.a", 100)).isEqualTo(1);
		release.countDown();
		first.join(5000);
		waiter.join(5000);

		assertThat(loads).hasValue(1);
		assertThat(second[0].source()).isEqualTo(Source.SHARED);
		assertThat(second[0].data()).isEqualTo(DATA);
		assertThat(meters.counter("captureu.reference.cache", "result", "shared").count()).isEqualTo(1);
	}

	@Test
	void 먼저_부른_쪽이_실패해도_그_실패를_물려받지_않고_직접_부른다() throws Exception {
		CountDownLatch loading = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Thread first = Thread.ofVirtual().start(() -> {
			try {
				cache.getOrLoad("ref.a", 100, "900", () -> {
					loading.countDown();
					await(release);
					throw new IllegalStateException("다른 분석이 멈춤");
				});
			}
			catch (IllegalStateException expected) {
				// 먼저 부른 분석은 자기 실패를 그대로 받는다
			}
		});
		assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
		Lookup[] second = new Lookup[1];
		Thread waiter = Thread.ofVirtual().start(() -> second[0] = cache.getOrLoad("ref.a", 100, "900", () -> DATA));
		release.countDown();
		first.join(5000);
		waiter.join(5000);

		assertThat(second[0]).isNotNull();
		assertThat(second[0].source()).isEqualTo(Source.LOADED);
		assertThat(second[0].data()).isEqualTo(DATA);
	}

	@Test
	void 부르다_실패하면_저장하지_않고_그대로_올린다() {
		assertThatThrownBy(() -> cache.getOrLoad("ref.a", 100, "900", () -> {
			throw new IllegalArgumentException("x");
		})).isInstanceOf(IllegalArgumentException.class);
		verify(ops, never()).set(eq("captureu:reference:v2:ref.a:100"), anyString(), any(Duration.class));
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
