package com.captureu.instagram.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Duration;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.captureu.instagram.account.ReferenceAccountReader.ReferenceAccountData;
import com.captureu.instagram.graph.GraphApiClient;
import com.captureu.instagram.graph.GraphApiErrorType;
import com.captureu.instagram.graph.GraphApiException;
import com.captureu.instagram.graph.GraphApiProperties;
import com.captureu.instagram.graph.GraphApiUsageTracker;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ReferenceAccountReaderTest {

	private static final String OWNER_URL = "https://graph.facebook.com/v27.0/100";

	private MockRestServiceServer server;
	private ReferenceAccountReader reader;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		ObjectMapper mapper = JsonMapper.builder().build();
		GraphApiProperties props = new GraphApiProperties("https://graph.facebook.com", "v27.0", "EAAtest", "100",
				Duration.ofSeconds(1), Duration.ofSeconds(1), 0, Duration.ofMillis(1));
		reader = new ReferenceAccountReader(
				new GraphApiClient(builder, props, new GraphApiUsageTracker(mapper, Clock.systemUTC()), mapper));
	}

	@Test
	void 아이디는_앞의_골뱅이와_공백을_떼고_소문자로_바꾼다() {
		assertThat(ReferenceAccountReader.normalizeUsername("  @Some.User_1 ")).isEqualTo("some.user_1");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "a b", "abc){id}", "한글아이디", "abcdefghijabcdefghijabcdefghij1" })
	void 아이디_규칙에_맞지_않으면_호출하지_않고_거절한다(String raw) {
		server.expect(never(), requestTo(Matchers.anything()));

		assertThatThrownBy(() -> ReferenceAccountReader.normalizeUsername(raw))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void next가_없어도_커서와_꽉_찬_페이지면_다음_페이지를_받는다() {
		// 실제 Business Discovery 응답처럼 paging.next 없이 커서만 준다
		server.expect(once(), requestTo(Matchers.allOf(Matchers.startsWith(OWNER_URL),
				Matchers.containsString("business_discovery.username(ref.a)"), Matchers.containsString("media.limit(50)"))))
			.andRespond(json("""
					{"business_discovery":{"id":"900","username":"ref.a","followers_count":78958,"media_count":1152,
					 "media":{"data":[%s,%s,%s],"paging":{"cursors":{"after":"CUR1"}}}}}
					""".formatted(reel("r1", 14728), feed("f1"), reels("p", 2, 49))));
		server.expect(once(), requestTo(Matchers.containsString("media.after(CUR1).limit(10)")))
			.andRespond(json("""
					{"business_discovery":{"media":{"data":[%s],"paging":{"cursors":{"after":"CUR2"}}}}}
					""".formatted(reels("q", 0, 9))));
		ReadContext ctx = new ReadContext();

		ReferenceAccountData data = reader.read("100", "ref.a", 60, ctx);

		assertThat(data.profile().igId()).isEqualTo("900");
		assertThat(data.profile().followersCount()).isEqualTo(78958);
		assertThat(data.media()).hasSize(60);
		assertThat(data.media()).extracting(m -> m.item().id()).startsWith("r1", "f1").endsWith("q9");
		assertThat(data.media().get(0).viewCount()).isEqualTo(14728);
		assertThat(data.media().get(1).viewCount()).isNull();
		assertThat(data.media().get(1).item().likeCount()).isNull();
		assertThat(ctx.callCount()).isEqualTo(2);
		server.verify();
	}

	@Test
	void 새_게시물이_늘지_않으면_멈춘다() {
		String fifty = reels("p", 0, 49);
		server.expect(once(), requestTo(Matchers.startsWith(OWNER_URL)))
			.andRespond(json("""
					{"business_discovery":{"id":"900","media":{"data":[%s],"paging":{"cursors":{"after":"C1"}}}}}
					""".formatted(fifty)));
		server.expect(once(), requestTo(Matchers.containsString("media.after(C1)")))
			.andRespond(json("""
					{"business_discovery":{"media":{"data":[%s],"paging":{"cursors":{"after":"C2"}}}}}
					""".formatted(fifty)));

		assertThat(reader.read("100", "ref.a", 100, new ReadContext()).media()).hasSize(50);
		server.verify();
	}

	@Test
	void 커서_형식이_이상하면_다음_페이지를_받지_않는다() {
		server.expect(once(), requestTo(Matchers.startsWith(OWNER_URL)))
			.andRespond(json("""
					{"business_discovery":{"id":"900","media":{"data":[%s],"paging":{"cursors":{"after":"a){id}"}}}}}
					""".formatted(reels("p", 0, 49))));

		assertThat(reader.read("100", "ref.a", 100, new ReadContext()).media()).hasSize(50);
		server.verify();
	}

	@Test
	void 게시물이_적으면_한_번만_부른다() {
		server.expect(once(), requestTo(Matchers.startsWith(OWNER_URL)))
			.andRespond(json("""
					{"business_discovery":{"id":"900","username":"ref.a","media":{"data":[%s],"paging":{"cursors":{"after":"C"}}}}}
					""".formatted(reel("r1", 1))));

		assertThat(reader.read("100", "ref.a", 100, new ReadContext()).media()).hasSize(1);
		server.verify();
	}

	@Test
	void 개인_계정이나_없는_계정은_TARGET_NOT_FOUND() {
		server.expect(once(), requestTo(Matchers.startsWith(OWNER_URL)))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":{\"message\":\"Invalid user id\",\"code\":110,\"error_subcode\":2207013}}"));

		assertThatThrownBy(() -> reader.readProfile("100", "nobody", new ReadContext()))
			.isInstanceOfSatisfying(GraphApiException.class,
					e -> assertThat(e.getType()).isEqualTo(GraphApiErrorType.TARGET_NOT_FOUND));
	}

	private static String reel(String id, int views) {
		return """
				{"id":"%s","media_type":"VIDEO","media_product_type":"REELS","timestamp":"2026-11-20T08:00:00+0000",
				 "caption":"제발.. 의견 주세요","thumbnail_url":"https://t","permalink":"https://p","comments_count":17,"view_count":%d}
				""".formatted(id, views);
	}

	/** prefix+from ~ prefix+to 릴스 JSON을 쉼표로 이어 붙인다. */
	private static String reels(String prefix, int from, int to) {
		StringBuilder sb = new StringBuilder();
		for (int i = from; i <= to; i++) {
			if (sb.length() > 0) {
				sb.append(',');
			}
			sb.append(reel(prefix + i, 1000 + i));
		}
		return sb.toString();
	}

	private static String feed(String id) {
		return """
				{"id":"%s","media_type":"CAROUSEL_ALBUM","media_product_type":"FEED","timestamp":"2026-11-19T08:00:00+0000",
				 "caption":"피드","media_url":"https://m","permalink":"https://p","comments_count":8}
				""".formatted(id);
	}

	private static org.springframework.test.web.client.response.DefaultResponseCreator json(String body) {
		return withSuccess(body, MediaType.APPLICATION_JSON);
	}
}
