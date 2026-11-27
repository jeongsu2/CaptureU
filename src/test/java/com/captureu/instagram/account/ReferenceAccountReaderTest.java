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
	void 최근_게시물을_커서로_넘기며_최대_개수까지_받는다() {
		server.expect(once(), requestTo(Matchers.allOf(Matchers.startsWith(OWNER_URL),
				Matchers.containsString("business_discovery.username(ref.a)"), Matchers.containsString("media.limit(3)"))))
			.andRespond(json("""
					{"business_discovery":{"id":"900","username":"ref.a","followers_count":78958,"media_count":1152,
					 "media":{"data":[%s,%s],"paging":{"cursors":{"after":"CUR1"},"next":"https://x?access_token=***"}}}}
					""".formatted(reel("r1", 14728), feed("f1"))));
		server.expect(once(), requestTo(Matchers.containsString("media.after(CUR1).limit(1)")))
			.andRespond(json("""
					{"business_discovery":{"media":{"data":[%s],"paging":{"cursors":{"after":"CUR2"},"next":"n"}}}}
					""".formatted(reel("r2", 29624))));
		ReadContext ctx = new ReadContext();

		ReferenceAccountData data = reader.read("100", "ref.a", 3, ctx);

		assertThat(data.profile().igId()).isEqualTo("900");
		assertThat(data.profile().followersCount()).isEqualTo(78958);
		assertThat(data.media()).extracting(m -> m.item().id()).containsExactly("r1", "f1", "r2");
		assertThat(data.media().get(0).viewCount()).isEqualTo(14728);
		assertThat(data.media().get(1).viewCount()).isNull();
		assertThat(data.media().get(1).item().likeCount()).isNull();
		assertThat(ctx.callCount()).isEqualTo(2);
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
