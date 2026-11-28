package com.captureu.domain.tracking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.captureu.TestcontainersConfiguration;
import com.captureu.domain.instagram.client.InstagramAccountReader;
import com.captureu.domain.instagram.dto.MediaInsights;
import com.captureu.domain.instagram.dto.MediaItem;
import com.captureu.domain.tracking.dto.response.TrackedPostResponse;
import com.captureu.domain.tracking.service.TrackingService.CollectResult;

/** 추적을 켜면 게시 24시간 값을 쌓고, 과거 게시물의 24시간 중앙값과 비교해 초반 급상승을 알려 준다. 외부 호출은 가짜. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "captureu.instagram.graph.ig-user-id=100", "captureu.llm.anthropic.api-key=" })
@ActiveProfiles("test")
class TrackingIntegrationTest {

	@Autowired
	TrackingService trackingService;
	@Autowired
	JdbcTemplate jdbc;
	@MockitoBean
	InstagramAccountReader reader;
	@MockitoBean
	Clock clock;

	private final Instant start = Instant.parse("2026-11-01T00:00:00Z");

	@BeforeEach
	void setUp() {
		jdbc.update("delete from media_checkpoint");
		jdbc.update("delete from tracking");
		given(clock.instant()).willReturn(start);
	}

	private static MediaItem reel(String id, Instant postedAt) {
		return new MediaItem(id, "VIDEO", "REELS", postedAt, "첫 줄 " + id + "\n본문", null, null, "https://p/" + id, 1,
				1);
	}

	private static MediaInsights views(int views) {
		return new MediaInsights(views, views, 1, 0, 1, 3000, 40.0, null);
	}

	private CollectResult collectAt(Instant now) {
		given(clock.instant()).willReturn(now);
		return trackingService.collectDue();
	}

	@Test
	void 추적을_켜지_않으면_수집하지_않는다() {
		assertThat(trackingService.collectDue().tracking()).isFalse();
		verify(reader, never()).readRecentMedia(any(), anyInt(), any());

		trackingService.change(true);
		trackingService.change(false);

		assertThat(trackingService.status().enabled()).isFalse();
		assertThat(trackingService.collectDue().tracking()).isFalse();
	}

	@Test
	void 게시_24시간_조회수를_과거_게시물의_같은_나이_중앙값과_비교한다() {
		trackingService.change(true);
		List<MediaItem> reels = List.of(reel("r1", start), reel("r2", start.plus(Duration.ofDays(1))),
				reel("r3", start.plus(Duration.ofDays(2))), reel("r4", start.plus(Duration.ofDays(3))),
				new MediaItem("f1", "IMAGE", "FEED", start.plus(Duration.ofDays(3)), "피드", null, null, null, 1, 1));
		given(reader.readRecentMedia(eq("100"), anyInt(), any())).willReturn(reels);
		for (MediaItem r : reels.subList(0, 3)) {
			given(reader.readInsights(eq(r), any())).willReturn(views(1000));
		}
		given(reader.readInsights(eq(reels.get(3)), any())).willReturn(views(3000));

		// 각 릴스가 24시간이 된 직후마다 수집한다 (다른 릴스의 48·72시간 값도 함께 쌓인다)
		for (int day = 1; day <= 4; day++) {
			CollectResult r = collectAt(start.plus(Duration.ofDays(day)).plus(Duration.ofMinutes(5)));
			assertThat(r.tracking()).isTrue();
			assertThat(r.failed()).isZero();
		}
		// 같은 시각에 다시 돌아도 이미 잰 나이는 다시 재지 않는다
		assertThat(collectAt(start.plus(Duration.ofDays(4)).plus(Duration.ofMinutes(6))).saved()).isZero();

		List<TrackedPostResponse> posts = trackingService.status().posts();
		assertThat(posts).extracting(TrackedPostResponse::mediaId).containsExactly("r4", "r3", "r2", "r1");
		TrackedPostResponse latest = posts.get(0);
		assertThat(latest.checkpointHours()).isEqualTo(24);
		assertThat(latest.captionFirstLine()).isEqualTo("첫 줄 r4");
		assertThat(latest.baselineViews()).isEqualTo(1000.0);
		assertThat(latest.baselineCount()).isEqualTo(3);
		assertThat(latest.multiple()).isEqualTo(3.0);
		assertThat(latest.surge()).isTrue();
		// 가장 먼저 올린 게시물은 비교할 과거가 없다
		assertThat(posts.get(3).baselineViews()).isNull();
		assertThat(jdbc.queryForObject("select count(*) from media_checkpoint where media_id = 'f1'", Integer.class))
			.isZero();
	}
}
