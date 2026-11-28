package com.captureu.domain.instagram.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.captureu.domain.instagram.config.GraphApiProperties;
import com.captureu.domain.instagram.exception.GraphApiErrorType;
import com.captureu.domain.instagram.exception.GraphApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class GraphApiClientTest {

	private static final String TOKEN = "EAAtest_token_value_1234567890";
	private static final String MEDIA_URL = "https://graph.facebook.com/v27.0/123/media";

	private final ObjectMapper objectMapper = JsonMapper.builder().build();
	private MockRestServiceServer server;
	private GraphApiUsageTracker usageTracker;
	private GraphApiClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		usageTracker = new GraphApiUsageTracker(objectMapper, Clock.systemUTC());
		GraphApiProperties properties = new GraphApiProperties("https://graph.facebook.com", "v27.0", TOKEN, "123",
				Duration.ofSeconds(1), Duration.ofSeconds(1), 3, Duration.ofMillis(1));
		client = new GraphApiClient(builder, properties, usageTracker, objectMapper);
	}

	@Test
	void 성공하면_JSON을_돌려주고_호출_한도_헤더를_기록한다() {
		HttpHeaders headers = new HttpHeaders();
		headers.add("x-business-use-case-usage",
				"{\"999\":[{\"type\":\"instagram\",\"call_count\":12,\"total_cputime\":3,\"total_time\":5,\"estimated_time_to_regain_access\":0}]}");
		server.expect(once(), requestTo(Matchers.startsWith(MEDIA_URL)))
			.andExpect(queryParam("access_token", TOKEN))
			.andExpect(queryParam("fields", "id,caption"))
			.andRespond(withSuccess("{\"data\":[{\"id\":\"1\",\"caption\":\"첫 줄\"}]}", MediaType.APPLICATION_JSON)
				.headers(headers));

		JsonNode result = client.get("123/media", Map.of("fields", "id,caption"));

		assertThat(result.path("data").get(0).path("caption").asString()).isEqualTo("첫 줄");
		assertThat(usageTracker.latest()).hasValueSatisfying(u -> assertThat(u.maxPercent()).isEqualTo(12));
		server.verify();
	}

	@Test
	void 중괄호가_들어간_fields도_그대로_인코딩해서_보낸다() {
		server.expect(once(), requestTo(Matchers.containsString("business_discovery.username(abc)%7Bmedia_count%7D")))
			.andRespond(withSuccess("{\"business_discovery\":{\"media_count\":3}}", MediaType.APPLICATION_JSON));

		JsonNode result = client.get("123", Map.of("fields", "business_discovery.username(abc){media_count}"));

		assertThat(result.path("business_discovery").path("media_count").asInt()).isEqualTo(3);
	}

	@Test
	void 일시_장애는_다시_시도해서_성공하면_결과를_돌려준다() {
		server.expect(once(), requestTo(Matchers.startsWith(MEDIA_URL)))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":{\"message\":\"temporary\",\"code\":100,\"is_transient\":true}}"));
		server.expect(once(), requestTo(Matchers.startsWith(MEDIA_URL)))
			.andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

		JsonNode result = client.get("123/media", Map.of());

		assertThat(result.path("data").isArray()).isTrue();
		server.verify();
	}

	@Test
	void 호출_한도_초과는_바로_다시_시도하지_않는다() {
		server.expect(once(), requestTo(Matchers.startsWith(MEDIA_URL)))
			.andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
				.body(error(80002, null, "Application request limit reached")));

		assertThatThrownBy(() -> client.get("123/media", Map.of()))
			.isInstanceOfSatisfying(GraphApiException.class, e -> {
				assertThat(e.getType()).isEqualTo(GraphApiErrorType.RATE_LIMITED);
				assertThat(e.getAttempts()).isEqualTo(1);
			});
		server.verify();
	}

	@Test
	void 값의_더하기_기호는_공백으로_읽히지_않게_인코딩한다() {
		server.expect(once(), requestTo(Matchers.containsString("since=2026-11-01T00:00:00%2B09:00")))
			.andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

		client.get("123/media", Map.of("since", "2026-11-01T00:00:00+09:00"));

		server.verify();
	}

	@Test
	void 돌려주는_응답의_paging_next에서도_토큰을_가린다() {
		server.expect(once(), requestTo(Matchers.startsWith(MEDIA_URL)))
			.andRespond(withSuccess("""
					{"data":[],"paging":{"cursors":{"after":"C1"},"next":"https://graph.facebook.com/v27.0/123/media?access_token=%s&after=C1"},
					 "business_discovery":{"media":{"paging":{"next":"https://x?access_token=%s"}}}}
					""".formatted(TOKEN, TOKEN), MediaType.APPLICATION_JSON));

		JsonNode result = client.get("123/media", Map.of());

		assertThat(result.toString()).doesNotContain(TOKEN);
		assertThat(result.path("paging").path("cursors").path("after").asString()).isEqualTo("C1");
		assertThat(result.path("paging").has("next")).isTrue();
	}

	@Test
	void 성공_응답인데_JSON이_아니면_일시_장애로_보고_다시_시도한다() {
		server.expect(times(4), requestTo(Matchers.startsWith(MEDIA_URL)))
			.andRespond(withSuccess("<html>gateway</html>", MediaType.TEXT_HTML));

		assertThatThrownBy(() -> client.get("123/media", Map.of()))
			.isInstanceOfSatisfying(GraphApiException.class, e -> {
				assertThat(e.getType()).isEqualTo(GraphApiErrorType.TEMPORARY);
				assertThat(e.getAttempts()).isEqualTo(4);
			});
	}

	@Test
	void 프로그래밍_오류나_Error는_감추지_않고_그대로_올린다() {
		// 기대한 요청이 없는데 호출하면 MockRestServiceServer가 AssertionError를 던진다
		assertThatThrownBy(() -> client.get("123/media", Map.of())).isInstanceOf(AssertionError.class);
	}

	@Test
	void 설정값을_출력해도_토큰이_보이지_않는다() {
		GraphApiProperties properties = new GraphApiProperties("https://graph.facebook.com", "v27.0", TOKEN, "123",
				Duration.ofSeconds(1), Duration.ofSeconds(1), 3, Duration.ofMillis(1));

		assertThat(properties.toString()).doesNotContain(TOKEN).contains("accessToken=***");
	}

	@Test
	void 토큰_무효는_다시_시도하지_않는다() {
		server.expect(once(), requestTo(Matchers.startsWith(MEDIA_URL)))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body(error(190, 464, "Sessions for the user are not allowed")));

		assertThatThrownBy(() -> client.get("123/media", Map.of()))
			.isInstanceOfSatisfying(GraphApiException.class, e -> {
				assertThat(e.getType()).isEqualTo(GraphApiErrorType.TOKEN_INVALID);
				assertThat(e.getSubcode()).isEqualTo(464);
				assertThat(e.getAttempts()).isEqualTo(1);
			});
		server.verify();
	}

	@Test
	void 개인_계정이나_없는_계정은_TARGET_NOT_FOUND로_분류한다() {
		server.expect(once(), requestTo(Matchers.startsWith("https://graph.facebook.com/v27.0/123")))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body(error(110, 2207013, "Invalid user id")));

		assertThatThrownBy(() -> client.get("123", Map.of("fields", "business_discovery.username(nobody){id}")))
			.isInstanceOfSatisfying(GraphApiException.class,
					e -> assertThat(e.getType()).isEqualTo(GraphApiErrorType.TARGET_NOT_FOUND));
	}

	@Test
	void 일시_장애가_계속되면_최대_횟수까지_시도하고_실패한다() {
		server.expect(times(4), requestTo(Matchers.startsWith(MEDIA_URL)))
			.andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("<html>bad gateway</html>"));

		assertThatThrownBy(() -> client.get("123/media", Map.of()))
			.isInstanceOfSatisfying(GraphApiException.class, e -> {
				assertThat(e.getType()).isEqualTo(GraphApiErrorType.TEMPORARY);
				assertThat(e.getAttempts()).isEqualTo(4);
			});
		server.verify();
	}

	@Test
	void 네트워크_오류_메시지에서도_토큰을_가린다() {
		server.expect(times(4), requestTo(Matchers.startsWith(MEDIA_URL)))
			.andRespond(withException(new SocketTimeoutException("Read timed out")));

		assertThatThrownBy(() -> client.get("123/media", Map.of()))
			.isInstanceOfSatisfying(GraphApiException.class, e -> {
				assertThat(e.getType()).isEqualTo(GraphApiErrorType.TEMPORARY);
				assertThat(e.getMessage()).doesNotContain(TOKEN);
			});
	}

	@Test
	void 에러_메시지에_토큰이_들어_있어도_가린다() {
		server.expect(once(), requestTo(Matchers.startsWith(MEDIA_URL)))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body(error(100, null, "bad request for https://x?access_token=" + TOKEN + "&a=1")));

		assertThatThrownBy(() -> client.get("123/media", Map.of()))
			.isInstanceOfSatisfying(GraphApiException.class, e -> {
				assertThat(e.getType()).isEqualTo(GraphApiErrorType.INVALID_REQUEST);
				assertThat(e.getMessage()).doesNotContain(TOKEN).contains("access_token=***");
			});
	}

	@Test
	void 토큰이_없으면_호출하지_않고_실패한다() throws IOException {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer emptyServer = MockRestServiceServer.bindTo(builder).build();
		GraphApiClient noTokenClient = new GraphApiClient(builder,
				new GraphApiProperties("https://graph.facebook.com", "v27.0", "", "123", Duration.ofSeconds(1),
						Duration.ofSeconds(1), 3, Duration.ofMillis(1)),
				usageTracker, objectMapper);

		assertThatThrownBy(() -> noTokenClient.get("123/media", Map.of()))
			.isInstanceOfSatisfying(GraphApiException.class,
					e -> assertThat(e.getType()).isEqualTo(GraphApiErrorType.TOKEN_INVALID));
		emptyServer.verify();
	}

	private static String error(int code, Integer subcode, String message) {
		return "{\"error\":{\"message\":\"%s\",\"type\":\"OAuthException\",\"code\":%d%s,\"fbtrace_id\":\"abc\"}}"
			.formatted(message, code, subcode == null ? "" : ",\"error_subcode\":" + subcode);
	}
}
