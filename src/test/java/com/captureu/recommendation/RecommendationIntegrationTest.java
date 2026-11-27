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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.captureu.TestcontainersConfiguration;
import com.captureu.analysis.AnalysisResponse;
import com.captureu.analysis.AnalysisService;
import com.captureu.analysis.AnalysisStatus;
import com.captureu.instagram.account.AccountInfo;
import com.captureu.instagram.account.InstagramAccountReader;
import com.captureu.instagram.account.MediaInsights;
import com.captureu.instagram.account.MediaItem;
import com.captureu.instagram.account.ReferenceAccountReader;
import com.captureu.instagram.graph.GraphApiErrorType;
import com.captureu.instagram.graph.GraphApiException;

/** 분석이 끝난 뒤 API로 추천을 받고 다시 조회한다. 로컬 .env에 키가 있어도 실제 API를 부르지 않게 키를 비운다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "captureu.instagram.graph.ig-user-id=100", "captureu.llm.anthropic.api-key=" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationIntegrationTest {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	AnalysisService analysisService;
	@Autowired
	RecommendationRepository repository;

	@MockitoBean
	InstagramAccountReader reader;
	@MockitoBean
	ReferenceAccountReader referenceReader;

	private Long finishedAnalysis() {
		MediaItem reel = new MediaItem("rec-m1", "VIDEO", "REELS", Instant.parse("2026-11-01T00:00:00Z"),
				"이거 저만 몰랐나요?", "https://cdn/t.jpg", null, "https://p/1", 46, 57);
		given(reader.readAccount(eq("100"), any())).willReturn(new AccountInfo("100", "tester", 100, 1));
		given(reader.readAllMedia(eq("100"), any())).willReturn(List.of(reel));
		given(reader.readInsights(eq(reel), any()))
			.willReturn(new MediaInsights(10773, 4365, 61, 0, 171, 9728, 39.3, null));
		given(reader.readDemographics(eq("100"), any())).willReturn(List.of());
		AnalysisResponse response = analysisService.request("100");
		assertThat(response.status()).isEqualTo(AnalysisStatus.DONE);
		return response.id();
	}

	@Test
	void 키가_없으면_템플릿으로_추천하고_저장해서_다시_조회한다() throws Exception {
		Long analysisId = finishedAnalysis();

		String location = mockMvc
			.perform(post("/api/analyses/" + analysisId + "/recommendations").contentType(MediaType.APPLICATION_JSON)
				.content("{\"topic\":\"  겨울 출근룩 \"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.topic").value("겨울 출근룩"))
			.andExpect(jsonPath("$.generator").value("TEMPLATE"))
			.andExpect(jsonPath("$.fallbackReason").value("NO_API_KEY"))
			.andExpect(jsonPath("$.promptVersion").value("template-v1"))
			.andExpect(jsonPath("$.result.hooks.length()").value(5))
			.andExpect(jsonPath("$.result.hooks[0].rationale").isNotEmpty())
			.andExpect(jsonPath("$.result.thumbnail.overlayText").isNotEmpty())
			.andExpect(jsonPath("$.result.fewReferences").value(true))
			.andReturn()
			.getResponse()
			.getHeader("Location");

		mockMvc.perform(get(location))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.result.hooks[0].text").value("겨울 출근룩, 아직도 이렇게 하세요?"))
			.andExpect(jsonPath("$.result.notes[0]").value(org.hamcrest.Matchers.containsString("한 계정 스타일")));
		assertThat(repository.findAll()).anySatisfy(r -> assertThat(r.getAnalysisId()).isEqualTo(analysisId));
	}

	@Test
	void 주제가_없거나_분석이_없거나_실패한_분석이면_추천하지_않는다() throws Exception {
		Long analysisId = finishedAnalysis();
		mockMvc.perform(post("/api/analyses/" + analysisId + "/recommendations")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"topic\":\"  \"}")).andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/analyses/999999/recommendations").contentType(MediaType.APPLICATION_JSON)
			.content("{\"topic\":\"홈카페\"}")).andExpect(status().isNotFound());

		given(reader.readAccount(eq("100"), any()))
			.willThrow(new GraphApiException(GraphApiErrorType.TOKEN_INVALID, 400, 190, 464, "invalid"));
		Long failed = analysisService.request("100").id();
		mockMvc.perform(post("/api/analyses/" + failed + "/recommendations").contentType(MediaType.APPLICATION_JSON)
			.content("{\"topic\":\"홈카페\"}")).andExpect(status().isUnprocessableContent());
		mockMvc.perform(get("/api/recommendations/999999")).andExpect(status().isNotFound());
	}
}
