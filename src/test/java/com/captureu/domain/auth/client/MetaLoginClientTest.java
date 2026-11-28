package com.captureu.domain.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.captureu.domain.auth.client.MetaLoginClient.MetaLoginException;
import com.captureu.domain.auth.config.AuthProperties;
import com.captureu.domain.instagram.config.GraphApiProperties;

class MetaLoginClientTest {

	private static final String BASE = "https://graph.facebook.com/v27.0/";

	private MockRestServiceServer server;
	private MetaLoginClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		AuthProperties auth = new AuthProperties("app1", "SECRET123", "cfg9", "https://app.test/api/auth/callback",
				"key", false);
		GraphApiProperties graph = new GraphApiProperties("https://graph.facebook.com", "v27.0", null, null,
				Duration.ofSeconds(1), Duration.ofSeconds(1), 0, Duration.ofMillis(1));
		client = new MetaLoginClient(builder, auth, graph);
	}

	@Test
	void 로그인_창_주소에_구성_ID와_state를_넣고_scope는_넣지_않는다() {
		String url = client.authorizeUrl("abc123");

		assertThat(url).startsWith("https://www.facebook.com/v27.0/dialog/oauth?")
			.contains("client_id=app1", "config_id=cfg9", "state=abc123", "response_type=code",
					"redirect_uri=https://app.test/api/auth/callback")
			.doesNotContain("scope=", "SECRET123");
	}

	@Test
	void 코드를_토큰으로_바꾸고_인스타_계정을_찾는다() {
		server.expect(once(), requestTo(Matchers.startsWith(BASE + "oauth/access_token")))
			.andExpect(queryParam("code", "CODE1"))
			.andExpect(queryParam("client_secret", "SECRET123"))
			.andRespond(withSuccess("{\"access_token\":\"EAAuser\",\"token_type\":\"bearer\",\"expires_in\":5183944}",
					MediaType.APPLICATION_JSON));
		server.expect(once(), requestTo(Matchers.startsWith(BASE + "me/accounts")))
			.andExpect(queryParam("access_token", "EAAuser"))
			.andRespond(withSuccess("""
					{"data":[{"id":"p1"},{"id":"p2","instagram_business_account":{"id":"1784","username":"me.ig"}}]}
					""", MediaType.APPLICATION_JSON));

		var token = client.exchangeCode("CODE1");
		var account = client.findInstagramAccount(token.accessToken());

		assertThat(token.expiresIn()).isEqualTo(5183944L);
		assertThat(token.toString()).doesNotContain("EAAuser");
		assertThat(account).hasValueSatisfying(a -> {
			assertThat(a.igUserId()).isEqualTo("1784");
			assertThat(a.username()).isEqualTo("me.ig");
		});
	}

	@Test
	void 실패해도_시크릿과_토큰을_메시지에_남기지_않는다() {
		server.expect(once(), requestTo(Matchers.startsWith(BASE + "me/accounts")))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":{\"message\":\"Invalid token EAAleak for app secret SECRET123\",\"code\":190}}"));

		assertThatThrownBy(() -> client.findInstagramAccount("EAAleak")).isInstanceOf(MetaLoginException.class)
			.message()
			.doesNotContain("EAAleak", "SECRET123");
	}
}
