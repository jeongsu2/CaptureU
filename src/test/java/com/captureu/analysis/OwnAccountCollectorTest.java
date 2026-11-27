package com.captureu.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.captureu.analysis.OwnAccountCollector.CollectedAccount;
import com.captureu.instagram.account.AccountInfo;
import com.captureu.instagram.account.InstagramAccountReader;
import com.captureu.instagram.account.MediaInsights;
import com.captureu.instagram.account.MediaItem;
import com.captureu.instagram.account.ReadContext;
import com.captureu.instagram.graph.GraphApiErrorType;
import com.captureu.instagram.graph.GraphApiException;

class OwnAccountCollectorTest {

	private static final int MEDIA = 40;
	private static final long CALL_MS = 50;

	private final InstagramAccountReader reader = mock(InstagramAccountReader.class);
	private final List<MediaItem> media = new ArrayList<>();
	private final AtomicInteger inFlight = new AtomicInteger();
	private final AtomicInteger maxInFlight = new AtomicInteger();
	private final AtomicInteger insightCalls = new AtomicInteger();

	@BeforeEach
	void setUp() {
		for (int i = 0; i < MEDIA; i++) {
			media.add(new MediaItem("m" + i, "VIDEO", "REELS", Instant.parse("2026-11-01T00:00:00Z"), "c", "t", null,
					"p", 1, 1));
		}
		given(reader.readAccount(eq("100"), any())).willReturn(new AccountInfo("100", "me", 1, MEDIA));
		given(reader.readAllMedia(eq("100"), any())).willReturn(media);
		given(reader.readDemographics(eq("100"), any())).willReturn(List.of());
	}

	@Test
	void 동시_호출_수를_넘지_않고_병렬로_받으며_게시물_순서를_유지한다() {
		given(reader.readInsights(any(), any())).willAnswer(inv -> {
			MediaItem item = inv.getArgument(0);
			slowCall();
			return new MediaInsights(Integer.parseInt(item.id().substring(1)), null, null, null, null, null, null, null);
		});
		OwnAccountCollector collector = new OwnAccountCollector(reader, 8);

		long start = System.nanoTime();
		CollectedAccount result = collector.collect("100", new ReadContext());
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		assertThat(result.media()).extracting(m -> m.item().id()).containsExactlyElementsOf(
				media.stream().map(MediaItem::id).toList());
		assertThat(result.media()).allSatisfy(
				m -> assertThat(m.insights().views()).isEqualTo(Integer.parseInt(m.item().id().substring(1))));
		assertThat(maxInFlight.get()).isLessThanOrEqualTo(8).isGreaterThan(1);
		// 순서대로면 40 × 50ms = 2초. 8개씩이면 약 0.25초
		assertThat(elapsedMs).isLessThan(MEDIA * CALL_MS / 2);
	}

	@Test
	void 동시_호출_수가_1이면_순서대로_부른다() {
		given(reader.readInsights(any(), any())).willAnswer(inv -> {
			slowCall();
			return new MediaInsights(1, null, null, null, null, null, null, null);
		});

		new OwnAccountCollector(reader, 1).collect("100", new ReadContext());

		assertThat(maxInFlight.get()).isEqualTo(1);
	}

	@Test
	void 토큰이_무효가_되면_남은_호출을_멈추고_그_에러를_올린다() {
		given(reader.readInsights(any(), any())).willAnswer(inv -> {
			MediaItem item = inv.getArgument(0);
			slowCall();
			if (item.id().equals("m3")) {
				throw new GraphApiException(GraphApiErrorType.TOKEN_INVALID, 400, 190, 464, "Sessions not allowed");
			}
			return new MediaInsights(1, null, null, null, null, null, null, null);
		});
		OwnAccountCollector collector = new OwnAccountCollector(reader, 2);

		assertThatThrownBy(() -> collector.collect("100", new ReadContext()))
			.isInstanceOfSatisfying(GraphApiException.class,
					e -> assertThat(e.getType()).isEqualTo(GraphApiErrorType.TOKEN_INVALID));
		// 2개씩 부르다가 4번째에서 실패하면, 40개를 다 부르지 않고 멈춘다
		assertThat(insightCalls.get()).isLessThan(MEDIA / 2);
	}

	@Test
	void 동시_호출_수는_1_이상이어야_한다() {
		assertThatThrownBy(() -> new OwnAccountCollector(reader, 0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 호출_수는_여러_스레드에서_세도_정확하다() throws Exception {
		ReadContext ctx = new ReadContext();
		List<Thread> threads = new ArrayList<>();
		for (int t = 0; t < 16; t++) {
			threads.add(Thread.ofVirtual().start(() -> {
				for (int i = 0; i < 1000; i++) {
					ctx.addCalls(1);
				}
			}));
		}
		for (Thread t : threads) {
			t.join();
		}

		assertThat(ctx.callCount()).isEqualTo(16_000);
	}

	private void slowCall() throws InterruptedException {
		insightCalls.incrementAndGet();
		int now = inFlight.incrementAndGet();
		maxInFlight.accumulateAndGet(now, Math::max);
		try {
			Thread.sleep(CALL_MS);
		}
		finally {
			inFlight.decrementAndGet();
		}
	}
}
