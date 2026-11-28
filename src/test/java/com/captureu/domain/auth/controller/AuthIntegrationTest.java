package com.captureu.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.captureu.TestcontainersConfiguration;
import com.captureu.domain.analysis.entity.Analysis;
import com.captureu.domain.analysis.repository.AnalysisRepository;
import com.captureu.domain.auth.client.MetaLoginClient;
import com.captureu.domain.auth.client.MetaLoginClient.InstagramAccount;
import com.captureu.domain.auth.client.MetaLoginClient.TokenResponse;
import com.captureu.domain.auth.service.CurrentAccountResolver;
import com.captureu.domain.auth.util.TokenCipher;

/** 인스타 로그인 → 세션 → 내 분석만 보기. Meta 호출은 가짜. .env 계정 대체(env-fallback)는 끈다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "captureu.auth.app-id=app1", "captureu.auth.app-secret=secret",
		"captureu.auth.config-id=cfg1", "captureu.auth.token-encryption-key=" + AuthIntegrationTest.KEY,
		"captureu.auth.env-fallback=false", "captureu.instagram.graph.ig-user-id=",
		"captureu.llm.anthropic.api-key=" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

	static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AnalysisRepository analysisRepository;
	@MockitoBean
	MetaLoginClient metaClient;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from app_user");
		given(metaClient.authorizeUrl(anyString()))
			.willAnswer(inv -> "https://www.facebook.com/v27.0/dialog/oauth?state=" + inv.getArgument(0));
		given(metaClient.exchangeCode("CODE1")).willReturn(new TokenResponse("EAAuser-token", null));
		given(metaClient.findInstagramAccount("EAAuser-token"))
			.willReturn(Optional.of(new InstagramAccount("ig-me", "me.ig")));
	}

	/** 로그인 창으로 보낸 뒤 Meta가 돌려준 것처럼 콜백을 부른다. */
	private MockHttpSession login() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String location = mvc.perform(get("/api/auth/login").session(session))
			.andExpect(status().isFound())
			.andReturn()
			.getResponse()
			.getHeader("Location");
		String state = location.substring(location.indexOf("state=") + "state=".length());
		mvc.perform(get("/api/auth/callback").param("code", "CODE1").param("state", state).session(session))
			.andExpect(status().isFound())
			.andExpect(header().string("Location", "/?login=ok"));
		return session;
	}

	@Test
	void 로그인하지_않으면_계정이_없고_분석을_요청할_수_없다() throws Exception {
		mvc.perform(get("/api/auth/me"))
			.andExpect(jsonPath("$.connected").value(false))
			.andExpect(jsonPath("$.loginAvailable").value(true));
		mvc.perform(post("/api/analyses")).andExpect(status().isUnauthorized());
	}

	@Test
	void 로그인하면_세션에_계정이_생기고_토큰은_암호화해_저장한다() throws Exception {
		MockHttpSession session = login();

		mvc.perform(get("/api/auth/me").session(session))
			.andExpect(jsonPath("$.connected").value(true))
			.andExpect(jsonPath("$.igUserId").value("ig-me"))
			.andExpect(jsonPath("$.username").value("me.ig"))
			.andExpect(jsonPath("$.source").value("LOGIN"));
		String stored = jdbc.queryForObject("select access_token_enc from app_user where ig_user_id = 'ig-me'",
				String.class);
		assertThat(stored).doesNotContain("EAAuser");
		assertThat(new TokenCipher(KEY).decrypt(stored)).isEqualTo("EAAuser-token");
		assertThat(session.getAttribute(CurrentAccountResolver.SESSION_KEY)).isEqualTo("ig-me");

		mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isNoContent());
		mvc.perform(get("/api/auth/me")).andExpect(jsonPath("$.connected").value(false));
	}

	@Test
	void state가_다르거나_취소하면_로그인하지_않는다() throws Exception {
		MockHttpSession session = new MockHttpSession();
		mvc.perform(get("/api/auth/login").session(session));

		mvc.perform(get("/api/auth/callback").param("code", "CODE1").param("state", "forged").session(session))
			.andExpect(header().string("Location", "/?login=invalid"));
		mvc.perform(get("/api/auth/callback").param("error", "access_denied").session(session))
			.andExpect(header().string("Location", "/?login=cancelled"));
		assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isZero();
	}

	@Test
	void 남의_분석은_없는_것처럼_404이고_내_분석은_볼_수_있다() throws Exception {
		Long mine = analysisRepository.save(Analysis.start("ig-me", Instant.now())).getId();
		Long others = analysisRepository.save(Analysis.start("ig-other", Instant.now())).getId();
		MockHttpSession session = login();

		mvc.perform(get("/api/analyses/" + mine).session(session)).andExpect(status().isOk());
		mvc.perform(get("/api/analyses/" + others).session(session)).andExpect(status().isNotFound());
		mvc.perform(get("/api/analyses/" + others + "/report").session(session)).andExpect(status().isNotFound());
		mvc.perform(get("/api/analyses/" + others + "/thumbnails").session(session))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/analyses/" + others + "/effects").session(session)).andExpect(status().isNotFound());
	}
}
