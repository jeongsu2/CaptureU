package com.captureu.instagram.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.captureu.instagram.graph.GraphApiClient;
import com.captureu.instagram.graph.GraphApiErrorType;
import com.captureu.instagram.graph.GraphApiException;
import com.captureu.instagram.graph.GraphApiProperties;
import com.captureu.instagram.graph.GraphApiUsageTracker;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class InstagramAccountReaderTest {

	private static final String BASE = "https://graph.facebook.com/v27.0/";

	private MockRestServiceServer server;
	private InstagramAccountReader reader;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		ObjectMapper mapper = JsonMapper.builder().build();
		GraphApiProperties props = new GraphApiProperties("https://graph.facebook.com", "v27.0", "EAAtest", "100",
				Duration.ofSeconds(1), Duration.ofSeconds(1), 0, Duration.ofMillis(1));
		reader = new InstagramAccountReader(
				new GraphApiClient(builder, props, new GraphApiUsageTracker(mapper, Clock.systemUTC()), mapper));
	}

	@Test
	void 게시물은_paging_next가_없을_때까지_커서로_받는다() {
		server.expect(once(), requestTo(Matchers.startsWith(BASE + "100/media")))
			.andExpect(queryParam("limit", "50"))
			.andRespond(json("""
					{"data":[%s],"paging":{"cursors":{"after":"CUR1"},"next":"https://graph.facebook.com/x?access_token=EAAtest"}}
					""".formatted(media("1", "REELS", "VIDEO"))));
		server.expect(once(), requestTo(Matchers.startsWith(BASE + "100/media")))
			.andExpect(queryParam("after", "CUR1"))
			.andRespond(json("""
					{"data":[%s],"paging":{"cursors":{"after":"CUR2"}}}
					""".formatted(media("2", "FEED", "CAROUSEL_ALBUM"))));
		ReadContext ctx = new ReadContext();

		List<MediaItem> items = reader.readAllMedia("100", ctx);

		assertThat(items).extracting(MediaItem::id).containsExactly("1", "2");
		assertThat(items.get(0).postedAt()).isEqualTo(Instant.parse("2026-11-27T15:39:59Z"));
		assertThat(items.get(0).isReels()).isTrue();
		assertThat(ctx.callCount()).isEqualTo(2);
		server.verify();
	}

	@Test
	void 피드_게시물은_media_url을_썸네일로_쓴다() {
		MediaItem feed = new MediaItem("2", "CAROUSEL_ALBUM", "FEED", Instant.now(), "c", null, "https://img", "p", 1, 1);
		MediaItem reelWithoutThumb = new MediaItem("3", "VIDEO", "REELS", Instant.now(), "c", null, "https://v.mp4",
				"p", 1, 1);

		assertThat(feed.coverImageUrl()).isEqualTo("https://img");
		assertThat(reelWithoutThumb.coverImageUrl()).isNull();
	}

	@Test
	void 릴스_인사이트는_한_번에_받는다() {
		server.expect(once(), requestTo(Matchers.startsWith(BASE + "1/insights")))
			.andExpect(queryParam("metric", String.join(",", InstagramAccountReader.REELS_METRICS)))
			.andRespond(json("""
					{"data":[
					 {"name":"views","period":"lifetime","values":[{"value":10773}]},
					 {"name":"saved","period":"lifetime","values":[{"value":61}]},
					 {"name":"ig_reels_avg_watch_time","period":"lifetime","values":[{"value":9728}]},
					 {"name":"reels_skip_rate","period":"lifetime","values":[{"value":39.3}]}]}
					"""));

		MediaInsights insights = reader.readInsights(reel("1"), new ReadContext());

		assertThat(insights.views()).isEqualTo(10773);
		assertThat(insights.saved()).isEqualTo(61);
		assertThat(insights.avgWatchTimeMs()).isEqualTo(9728);
		assertThat(insights.skipRate()).isEqualTo(39.3);
		assertThat(insights.isFailed()).isFalse();
	}

	@Test
	void 일부_지표가_거부되면_지표별로_다시_받고_거부된_지표는_다음부터_빼고_요청한다() {
		ReadContext ctx = new ReadContext();
		// 1번 게시물: 한 번에 요청 → 거부 → 지표별 7번 (skip_rate만 거부)
		server.expect(once(), requestTo(Matchers.startsWith(BASE + "1/insights")))
			.andRespond(error(100, "(#100) metric[6] must be one of the following values"));
		for (String metric : InstagramAccountReader.REELS_METRICS) {
			if (metric.equals("reels_skip_rate")) {
				server.expect(once(), requestTo(Matchers.startsWith(BASE + "1/insights")))
					.andExpect(queryParam("metric", metric))
					.andRespond(error(100, "unsupported metric"));
			}
			else {
				server.expect(once(), requestTo(Matchers.startsWith(BASE + "1/insights")))
					.andExpect(queryParam("metric", metric))
					.andRespond(json("{\"data\":[{\"name\":\"%s\",\"values\":[{\"value\":5}]}]}".formatted(metric)));
			}
		}
		// 2번 게시물: 거부된 지표를 빼고 한 번에 요청
		server.expect(once(), requestTo(Matchers.startsWith(BASE + "2/insights")))
			.andExpect(queryParam("metric", Matchers.not(Matchers.containsString("reels_skip_rate"))))
			.andRespond(json("{\"data\":[{\"name\":\"views\",\"values\":[{\"value\":7}]}]}"));

		MediaInsights first = reader.readInsights(reel("1"), ctx);
		MediaInsights second = reader.readInsights(reel("2"), ctx);

		assertThat(first.views()).isEqualTo(5);
		assertThat(first.skipRate()).isNull();
		assertThat(second.views()).isEqualTo(7);
		assertThat(ctx.callCount()).isEqualTo(9);
		server.verify();
	}

	@Test
	void 지표가_전부_거부되면_게시물_문제로_보고_다음_게시물은_전체_지표로_요청한다() {
		ReadContext ctx = new ReadContext();
		String allMetrics = String.join(",", InstagramAccountReader.REELS_METRICS);
		server.expect(once(), requestTo(Matchers.startsWith(BASE + "1/insights")))
			.andRespond(error(100, "(#100) Media posted before business account conversion"));
		for (int i = 0; i < InstagramAccountReader.REELS_METRICS.size(); i++) {
			server.expect(once(), requestTo(Matchers.startsWith(BASE + "1/insights")))
				.andRespond(error(100, "(#100) Media posted before business account conversion"));
		}
		server.expect(once(), requestTo(Matchers.startsWith(BASE + "2/insights")))
			.andExpect(queryParam("metric", allMetrics))
			.andRespond(json("{\"data\":[{\"name\":\"reels_skip_rate\",\"values\":[{\"value\":41.7}]}]}"));

		MediaInsights first = reader.readInsights(reel("1"), ctx);
		MediaInsights second = reader.readInsights(reel("2"), ctx);

		assertThat(first.isFailed()).isTrue();
		assertThat(first.error()).contains("business account conversion");
		assertThat(second.skipRate()).isEqualTo(41.7);
		server.verify();
	}

	@Test
	void 페이지가_바뀌며_같은_게시물이_두_번_와도_한_번만_담는다() {
		server.expect(once(), requestTo(Matchers.startsWith(BASE + "100/media")))
			.andRespond(json("""
					{"data":[%s,%s],"paging":{"cursors":{"after":"C1"},"next":"n"}}
					""".formatted(media("1", "REELS", "VIDEO"), media("2", "REELS", "VIDEO"))));
		server.expect(once(), requestTo(Matchers.startsWith(BASE + "100/media")))
			.andRespond(json("""
					{"data":[%s,%s],"paging":{"cursors":{"after":"C2"}}}
					""".formatted(media("2", "REELS", "VIDEO"), media("3", "REELS", "VIDEO"))));

		assertThat(reader.readAllMedia("100", new ReadContext())).extracting(MediaItem::id)
			.containsExactly("1", "2", "3");
	}

	@Test
	void 시각을_읽을_수_없는_게시물은_건너뛴다() {
		server.expect(once(), requestTo(Matchers.startsWith(BASE + "100/media")))
			.andRespond(json("""
					{"data":[{"id":"bad","media_type":"VIDEO","media_product_type":"REELS","timestamp":"어제"},
					 {"id":"nots","media_type":"VIDEO","media_product_type":"REELS"},%s]}
					""".formatted(media("1", "REELS", "VIDEO"))));

		assertThat(reader.readAllMedia("100", new ReadContext())).extracting(MediaItem::id).containsExactly("1");
	}

	@Test
	void 게시물_하나의_인사이트를_못_받아도_실패로_표시하고_계속한다() {
		server.expect(once(), requestTo(Matchers.startsWith(BASE + "1/insights")))
			.andRespond(error(10, "(#10) Media Posted Before Business Account Conversion"));

		MediaInsights insights = reader.readInsights(reel("1"), new ReadContext());

		assertThat(insights.isFailed()).isTrue();
		assertThat(insights.error()).contains("Business Account Conversion");
	}

	@Test
	void 토큰이_무효면_수집_전체를_멈춘다() {
		server.expect(once(), requestTo(Matchers.startsWith(BASE + "1/insights")))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":{\"message\":\"Sessions not allowed\",\"code\":190,\"error_subcode\":464}}"));

		assertThatThrownBy(() -> reader.readInsights(reel("1"), new ReadContext()))
			.isInstanceOfSatisfying(GraphApiException.class,
					e -> assertThat(e.getType()).isEqualTo(GraphApiErrorType.TOKEN_INVALID));
	}

	@Test
	void 연령대는_받을_수_있는_것만_모은다() {
		server.expect(once(), requestTo(Matchers.allOf(Matchers.startsWith(BASE + "100/insights"),
				Matchers.containsString("metric=follower_demographics"), Matchers.containsString("breakdown=age"))))
			.andRespond(json("""
					{"data":[{"name":"follower_demographics","total_value":{"breakdowns":[{"dimension_keys":["age"],
					"results":[{"dimension_values":["18-24"],"value":8632},{"dimension_values":["25-34"],"value":8376}]}]}}]}
					"""));
		server.expect(once(), requestTo(Matchers.containsString("breakdown=gender")))
			.andRespond(error(100, "(#100) Not enough followers"));
		server.expect(once(), requestTo(Matchers.containsString("metric=engaged_audience_demographics")))
			.andRespond(json("{\"data\":[]}"));

		List<DemographicRow> rows = reader.readDemographics("100", new ReadContext());

		assertThat(rows).containsExactly(new DemographicRow("follower_demographics", "age", "18-24", 8632),
				new DemographicRow("follower_demographics", "age", "25-34", 8376));
	}

	private static MediaItem reel(String id) {
		return new MediaItem(id, "VIDEO", "REELS", Instant.parse("2026-11-27T15:39:59Z"), "caption", "https://t",
				null, "p", 1, 1);
	}

	private static String media(String id, String product, String type) {
		return """
				{"id":"%s","media_type":"%s","media_product_type":"%s","timestamp":"2026-11-27T15:39:59+0000",
				 "caption":"첫 줄\\n둘째 줄","thumbnail_url":"https://t","permalink":"https://p","like_count":3,"comments_count":1}
				""".formatted(id, type, product);
	}

	private static org.springframework.test.web.client.response.DefaultResponseCreator json(String body) {
		return withSuccess(body, MediaType.APPLICATION_JSON);
	}

	private static org.springframework.test.web.client.response.DefaultResponseCreator error(int code, String msg) {
		return withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
			.body("{\"error\":{\"message\":\"%s\",\"code\":%d}}".formatted(msg, code));
	}
}
