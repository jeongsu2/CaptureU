package com.captureu.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.captureu.instagram.account.MediaItem;
import com.captureu.instagram.account.ReferenceAccountReader.ReferenceAccountData;
import com.captureu.instagram.account.ReferenceAccountReader.ReferenceMedia;
import com.captureu.instagram.account.ReferenceAccountReader.ReferenceProfile;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

class ReferenceAccountCacheTest {

	private static final ReferenceAccountData DATA = new ReferenceAccountData(
			new ReferenceProfile("900", "ref.a", 1, 1),
			List.of(new ReferenceMedia(new MediaItem("r1", "VIDEO", "REELS", Instant.parse("2026-11-01T00:00:00Z"),
					"첫 줄 😀", "https://t", null, "https://p", null, 3), 1234)));

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> ops = mock(ValueOperations.class);
	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
	private final ReferenceAccountCache cache = new ReferenceAccountCache(redis, JsonMapper.builder().build(), meters,
			Duration.ofHours(6));

	@Test
	void Redis가_안_되면_직접_불러서_분석을_계속한다() {
		given(redis.opsForValue()).willReturn(ops);
		given(ops.get(anyString())).willThrow(new RedisConnectionFailureException("down"));
		AtomicInteger loads = new AtomicInteger();

		ReferenceAccountCache.Lookup lookup = cache.getOrLoad("ref.a", 100, () -> {
			loads.incrementAndGet();
			return DATA;
		});

		assertThat(lookup.fromCache()).isFalse();
		assertThat(lookup.data()).isEqualTo(DATA);
		assertThat(loads).hasValue(1);
		assertThat(meters.counter("captureu.reference.cache", "result", "error").count()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void 같은_계정을_동시에_요청하면_한_번만_부른다() throws Exception {
		given(redis.opsForValue()).willReturn(ops);
		given(ops.get(anyString())).willReturn(null);
		AtomicInteger loads = new AtomicInteger();
		CountDownLatch loading = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		Thread first = Thread.ofVirtual().start(() -> cache.getOrLoad("ref.a", 100, () -> {
			loads.incrementAndGet();
			loading.countDown();
			await(release);
			return DATA;
		}));
		assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
		ReferenceAccountCache.Lookup[] second = new ReferenceAccountCache.Lookup[1];
		Thread waiter = Thread.ofVirtual().start(() -> second[0] = cache.getOrLoad("ref.a", 100, () -> {
			loads.incrementAndGet();
			return DATA;
		}));
		release.countDown();
		first.join(5000);
		waiter.join(5000);

		assertThat(loads).hasValue(1);
		assertThat(second[0].fromCache()).isTrue();
		assertThat(second[0].data()).isEqualTo(DATA);
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
