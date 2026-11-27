package com.captureu.analysis;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.captureu.TestcontainersConfiguration;
import com.captureu.instagram.account.AccountInfo;
import com.captureu.instagram.account.DemographicRow;
import com.captureu.instagram.account.InstagramAccountReader;
import com.captureu.instagram.account.MediaInsights;
import com.captureu.instagram.account.MediaItem;
import com.captureu.instagram.graph.GraphApiErrorType;
import com.captureu.instagram.graph.GraphApiException;

/** 실제 MySQL(Testcontainers)에 저장되는지 확인한다. 외부 API는 가짜로 바꾼다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "captureu.instagram.graph.ig-user-id=100")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalysisServiceIntegrationTest {

	@Autowired
	AnalysisService analysisService;
	@Autowired
	MockMvc mockMvc;
	@Autowired
	IgMediaRepository mediaRepository;
	@Autowired
	MediaMetricSnapshotRepository snapshotRepository;
	@Autowired
	AudienceDemographicRepository demographicRepository;

	@MockitoBean
	InstagramAccountReader reader;

	private final MediaItem reel = new MediaItem("m1", "VIDEO", "REELS", Instant.parse("2026-11-27T00:00:00Z"),
			"#광고 첫 줄\n둘째 줄", "https://cdn/t.jpg?oe=6750A000", null, "https://p/1", 46, 57);
	private final MediaItem feed = new MediaItem("m2", "CAROUSEL_ALBUM", "FEED", Instant.parse("2026-11-20T00:00:00Z"),
			null, null, "https://cdn/i.jpg", "https://p/2", 198, 6);

	@BeforeEach
	void setUp() {
		snapshotRepository.deleteAll();
		demographicRepository.deleteAll();
		given(reader.readAccount(eq("100"), any())).willReturn(new AccountInfo("100", "tester", 19664, 44));
		given(reader.readAllMedia(eq("100"), any())).willReturn(List.of(reel, feed));
		given(reader.readInsights(eq(reel), any()))
			.willReturn(new MediaInsights(10773, 4365, 61, 0, 171, 9728, 39.3, null));
		given(reader.readInsights(eq(feed), any())).willReturn(new MediaInsights(null, null, null, null, null,
				null, null, "(#10) Media Posted Before Business Account Conversion"));
		given(reader.readDemographics(eq("100"), any()))
			.willReturn(List.of(new DemographicRow("follower_demographics", "age", "18-24", 8632)));
	}

	@Test
	void 분석을_요청하면_게시물과_지표와_연령대가_저장된다() {
		AnalysisResponse response = analysisService.request("100");

		assertThat(response.status()).isEqualTo(AnalysisStatus.DONE);
		assertThat(response.mediaCount()).isEqualTo(2);
		assertThat(response.insightFailedCount()).isEqualTo(1);
		assertThat(response.reelsWithSkipRate()).isEqualTo(1);
		assertThat(response.username()).isEqualTo("tester");
		assertThat(response.durationMs()).isNotNull();

		IgMedia saved = mediaRepository.findById("m1").orElseThrow();
		assertThat(saved.getCaptionFirstLine()).isEqualTo("#광고 첫 줄");
		assertThat(saved.getThumbnailUrlExpiresAt()).isNotNull();
		assertThat(mediaRepository.findById("m2").orElseThrow().getThumbnailUrl()).isEqualTo("https://cdn/i.jpg");

		List<MediaMetricSnapshot> snapshots = snapshotRepository.findByAnalysisId(response.id());
		assertThat(snapshots).hasSize(2);
		assertThat(snapshots).filteredOn(s -> s.getMediaId().equals("m1"))
			.singleElement()
			.satisfies(s -> {
				assertThat(s.getSkipRate()).isEqualTo(39.3);
				assertThat(s.getViews()).isEqualTo(10773);
				assertThat(s.getAgeHours()).isGreaterThan(0);
			});
		assertThat(demographicRepository.findByAnalysisId(response.id())).hasSize(1);
	}

	@Test
	void 다시_분석하면_게시물은_덮어쓰고_지표는_한_줄_더_쌓인다() {
		AnalysisResponse first = analysisService.request("100");
		AnalysisResponse second = analysisService.request("100");

		assertThat(mediaRepository.findAllById(List.of("m1", "m2"))).hasSize(2);
		assertThat(snapshotRepository.findByAnalysisId(first.id())).hasSize(2);
		assertThat(snapshotRepository.findByAnalysisId(second.id())).hasSize(2);
	}

	@Test
	void 토큰이_무효면_분석은_실패로_기록된다() {
		given(reader.readAllMedia(eq("100"), any()))
			.willThrow(new GraphApiException(GraphApiErrorType.TOKEN_INVALID, 400, 190, 464, "Sessions not allowed"));

		AnalysisResponse response = analysisService.request("100");

		assertThat(response.status()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(response.failureType()).isEqualTo("TOKEN_INVALID");
		assertThat(response.finishedAt()).isNotNull();
	}

	@Test
	void API로_요청하고_조회한다() throws Exception {
		String location = mockMvc.perform(post("/api/analyses"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("DONE"))
			.andExpect(jsonPath("$.mediaCount").value(2))
			.andReturn()
			.getResponse()
			.getHeader("Location");

		mockMvc.perform(get(location)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("tester"));
		mockMvc.perform(get("/api/analyses/999999")).andExpect(status().isNotFound());
	}
}
