package com.captureu.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.captureu.TestcontainersConfiguration;
import com.captureu.analysis.AnalysisService;
import com.captureu.instagram.account.AccountInfo;
import com.captureu.instagram.account.InstagramAccountReader;
import com.captureu.instagram.account.MediaInsights;
import com.captureu.instagram.account.MediaItem;
import com.captureu.instagram.account.ReferenceAccountReader;

/** 채택 → 새 게시물 수집 → 자동 연결 / 확인 질문 / 사용자 결정 흐름 (#14). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "captureu.instagram.graph.ig-user-id=100", "captureu.llm.anthropic.api-key=" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EffectIntegrationTest {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	AnalysisService analysisService;
	@Autowired
	RecommendationService recommendationService;
	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	InstagramAccountReader reader;
	@MockitoBean
	ReferenceAccountReader referenceReader;

	private final Instant now = Instant.now();
	private final MediaItem old = reel("eff-old", now.minus(30, ChronoUnit.DAYS), "예전 게시물");

	private static MediaItem reel(String id, Instant postedAt, String caption) {
		return new MediaItem(id, "VIDEO", "REELS", postedAt, caption, "https://cdn/" + id + ".jpg", null,
				"https://p/" + id, 10, 1);
	}

	@BeforeEach
	void setUp() {
		// 다른 테스트의 채택이 섞이면 연결 대상이 달라진다
		jdbc.update("delete from adoption_rejection");
		jdbc.update("delete from recommendation_adoption");
		given(reader.readAccount(eq("100"), any())).willReturn(new AccountInfo("100", "tester", 100, 1));
		given(reader.readInsights(any(), any()))
			.willReturn(new MediaInsights(20000, 9000, 100, 5, 300, 15000, 55.0, null));
		given(reader.readDemographics(eq("100"), any())).willReturn(List.of());
	}

	private Long analyze(MediaItem... media) {
		given(reader.readAllMedia(eq("100"), any())).willReturn(List.of(media));
		return analysisService.request("100").id();
	}

	@Test
	void 채택한_문구로_올린_게시물은_자동_연결하고_애매하면_묻는다() throws Exception {
		Long first = analyze(old);
		var rec = recommendationService.recommend(first, "겨울 출근룩");
		Long usedAsIs = recommendationService.adopt(rec.id(), 0).id(); // "겨울 출근룩, 아직도 이렇게 하세요?"
		Long edited = recommendationService.adopt(rec.id(), 1).id(); // "겨울 출근룩 3가지 모음.zip"

		Long second = analyze(old,
				reel("eff-a", now.minus(30, ChronoUnit.MINUTES), "겨울 출근룩, 아직도 이렇게 하세요? 🧣\n본문"),
				reel("eff-b", now.minus(20, ChronoUnit.MINUTES), "출근룩 3가지 정리"));

		mockMvc.perform(get("/api/analyses/" + second + "/effects"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.adoptions[0].status").value("AUTO"))
			.andExpect(jsonPath("$.adoptions[0].post.mediaId").value("eff-a"))
			.andExpect(jsonPath("$.adoptions[0].post.excludedReason").value("YOUNG"))
			.andExpect(jsonPath("$.adoptions[1].status").value("ASK"))
			.andExpect(jsonPath("$.adoptions[1].candidate.mediaId").value("eff-b"))
			.andExpect(jsonPath("$.summary.matchedCount").value(1))
			.andExpect(jsonPath("$.summary.comparedCount").value(0))
			.andExpect(jsonPath("$.summary.headline").doesNotExist());

		// "이 게시물에 썼어요"
		mockMvc.perform(post("/api/adoptions/" + edited + "/match").contentType(MediaType.APPLICATION_JSON)
			.content("{\"analysisId\":%d,\"mediaId\":\"eff-b\",\"used\":true}".formatted(second)))
			.andExpect(status().isNoContent());
		// 자동 연결이 틀렸다면 "아니에요"로 떼고, 같은 게시물에 다시 붙이지 않는다
		mockMvc.perform(post("/api/adoptions/" + usedAsIs + "/match").contentType(MediaType.APPLICATION_JSON)
			.content("{\"analysisId\":%d,\"mediaId\":\"eff-a\",\"used\":false}".formatted(second)))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/analyses/" + second + "/effects"))
			.andExpect(jsonPath("$.adoptions[0].status").value("NOT_FOUND"))
			.andExpect(jsonPath("$.adoptions[1].status").value("CONFIRMED"))
			.andExpect(jsonPath("$.adoptions[1].post.mediaId").value("eff-b"));

		// 새 게시물에 다시 자동 연결됐다가 그것도 떼면, 처음 뗀 게시물로 돌아가지 않는다
		Long third = analyze(old,
				reel("eff-a", now.minus(30, ChronoUnit.MINUTES), "겨울 출근룩, 아직도 이렇게 하세요? 🧣\n본문"),
				reel("eff-b", now.minus(20, ChronoUnit.MINUTES), "출근룩 3가지 정리"),
				reel("eff-c", now.minus(10, ChronoUnit.MINUTES), "겨울 출근룩 아직도 이렇게 하세요!!"));
		mockMvc.perform(get("/api/analyses/" + third + "/effects"))
			.andExpect(jsonPath("$.adoptions[0].status").value("AUTO"))
			.andExpect(jsonPath("$.adoptions[0].post.mediaId").value("eff-c"));
		mockMvc.perform(post("/api/adoptions/" + usedAsIs + "/match").contentType(MediaType.APPLICATION_JSON)
			.content("{\"analysisId\":%d,\"mediaId\":\"eff-c\",\"used\":false}".formatted(third)))
			.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/analyses/" + third + "/effects"))
			.andExpect(jsonPath("$.adoptions[0].status").value("NOT_FOUND"));
		// 조회는 저장하지 않는다
		assertThat(jdbc.queryForObject("select count(*) from recommendation_adoption where match_status is not null",
				Integer.class)).isEqualTo(1);
	}

	@Test
	void 내_게시물이_아니거나_이미_연결된_게시물이거나_없는_채택이면_거절한다() throws Exception {
		Long first = analyze(old);
		var rec = recommendationService.recommend(first, "겨울 출근룩");
		Long a = recommendationService.adopt(rec.id(), 0).id();
		Long b = recommendationService.adopt(rec.id(), 1).id();
		mockMvc.perform(post("/api/adoptions/" + a + "/match").contentType(MediaType.APPLICATION_JSON)
			.content("{\"analysisId\":%d,\"mediaId\":\"eff-old\",\"used\":true}".formatted(first)))
			.andExpect(status().isNoContent());

		mockMvc.perform(post("/api/adoptions/" + b + "/match").contentType(MediaType.APPLICATION_JSON)
			.content("{\"analysisId\":%d,\"mediaId\":\"eff-old\",\"used\":true}".formatted(first)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("이 게시물은 이미 다른 채택 문구에 연결돼 있어요"));
		mockMvc.perform(post("/api/adoptions/" + b + "/match").contentType(MediaType.APPLICATION_JSON)
			.content("{\"analysisId\":%d,\"mediaId\":\"someone-else\",\"used\":true}".formatted(first)))
			.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/adoptions/999999/match").contentType(MediaType.APPLICATION_JSON)
			.content("{\"analysisId\":%d,\"mediaId\":\"eff-old\",\"used\":true}".formatted(first)))
			.andExpect(status().isNotFound());
		mockMvc.perform(post("/api/adoptions/" + b + "/match").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest());
		assertThat(jdbc.queryForObject("select match_status from recommendation_adoption where id = ?", String.class,
				a)).isEqualTo("CONFIRMED");
	}
}
