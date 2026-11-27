package com.captureu.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
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
import com.captureu.instagram.account.ReferenceAccountReader;
import com.captureu.instagram.account.ReferenceAccountReader.ReferenceAccountData;
import com.captureu.instagram.account.ReferenceAccountReader.ReferenceMedia;
import com.captureu.instagram.account.ReferenceAccountReader.ReferenceProfile;
import com.captureu.instagram.graph.GraphApiErrorType;
import com.captureu.instagram.graph.GraphApiException;
import com.captureu.reference.ReferenceAccount;
import com.captureu.reference.ReferenceAccountRepository;

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
	@Autowired
	AnalysisRepository analysisRepository;
	@Autowired
	AnalysisWriter writer;

	@Autowired
	ReferenceAccountRepository referenceRepository;
	@Autowired
	OutlierService outlierService;

	@MockitoBean
	InstagramAccountReader reader;
	@MockitoBean
	ReferenceAccountReader referenceReader;

	private final MediaItem reel = new MediaItem("m1", "VIDEO", "REELS", Instant.parse("2026-11-27T00:00:00Z"),
			"#광고 첫 줄\n둘째 줄", "https://cdn/t.jpg?oe=6750A000", null, "https://p/1", 46, 57);
	private final MediaItem feed = new MediaItem("m2", "CAROUSEL_ALBUM", "FEED", Instant.parse("2026-11-20T00:00:00Z"),
			null, null, "https://cdn/i.jpg", "https://p/2", 198, 6);

	@BeforeEach
	void setUp() {
		snapshotRepository.deleteAll();
		demographicRepository.deleteAll();
		referenceRepository.deleteAll();
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
	void 참고_계정도_같은_형태로_저장하고_하나가_실패해도_분석은_완료된다() {
		Instant now = Instant.parse("2026-11-28T00:00:00Z");
		referenceRepository.save(new ReferenceAccount("100", "ref.a", "900", 78958, now));
		referenceRepository.save(new ReferenceAccount("100", "ref.b", "901", 140058, now));
		MediaItem refReel = new MediaItem("r1", "VIDEO", "REELS", Instant.parse("2026-11-10T00:00:00Z"),
				"제발.. 의견 주세요", "https://cdn/r.jpg", null, "https://p/r1", null, 17);
		given(referenceReader.read(eq("100"), eq("ref.a"), anyInt(), any()))
			.willReturn(new ReferenceAccountData(new ReferenceProfile("900", "ref.a", 78958, 1152),
					List.of(new ReferenceMedia(refReel, 29624), new ReferenceMedia(reel, 1))));
		given(referenceReader.read(eq("100"), eq("ref.b"), anyInt(), any()))
			.willThrow(new GraphApiException(GraphApiErrorType.TARGET_NOT_FOUND, 400, 110, 2207013, "Invalid user id"));

		AnalysisResponse response = analysisService.request("100");

		assertThat(response.status()).isEqualTo(AnalysisStatus.DONE);
		assertThat(response.references())
			.extracting(r -> r.username() + ":" + r.status() + ":" + r.mediaCount() + ":" + r.apiCallCount())
			// 협업 게시물(m1)은 내 계정과 참고 계정 모두의 게시물로 센다
			.containsExactly("ref.a:DONE:2:0", "ref.b:FAILED:0:0");
		assertThat(response.references()).allSatisfy(r -> assertThat(r.durationMs()).isNotNull());
		assertThat(mediaRepository.findById("r1").orElseThrow().getOwnerIgUserId()).isEqualTo("900");
		// 협업 게시물의 소유자는 내 계정
		assertThat(mediaRepository.findById("m1").orElseThrow().getOwnerIgUserId()).isEqualTo("100");
		List<MediaMetricSnapshot> snapshots = snapshotRepository.findByAnalysisId(response.id());
		assertThat(snapshots).filteredOn(s -> s.getMediaId().equals("m1"))
			.extracting(MediaMetricSnapshot::getAccountIgId)
			.containsExactlyInAnyOrder("100", "900");
		assertThat(snapshots).filteredOn(s -> s.getMediaId().equals("r1"))
			.singleElement()
			.satisfies(s -> {
				assertThat(s.getAccountIgId()).isEqualTo("900");
				assertThat(s.getViews()).isEqualTo(29624);
				assertThat(s.getSkipRate()).isNull();
				assertThat(s.getLikeCount()).isNull();
			});
	}

	@Test
	void 참고_계정_수집_중_예상하지_못한_오류가_나도_내_계정_분석은_완료된다() {
		referenceRepository.save(new ReferenceAccount("100", "ref.a", "900", 1, Instant.now()));
		given(referenceReader.read(eq("100"), eq("ref.a"), anyInt(), any()))
			.willThrow(new IllegalStateException("예상 못한 오류"));

		AnalysisResponse response = analysisService.request("100");

		assertThat(response.status()).isEqualTo(AnalysisStatus.DONE);
		assertThat(response.references()).singleElement()
			.satisfies(r -> assertThat(r.failureType()).isEqualTo("UNEXPECTED"));
	}

	@Test
	void 분석이_끝나면_내_계정과_참고_계정별_아웃라이어를_계산한다() throws Exception {
		Instant old = Instant.now().minusSeconds(30L * 24 * 3600);
		List<MediaItem> mine = new ArrayList<>();
		int[] views = { 1000, 1200, 900, 1100, 5000 };
		for (int i = 0; i < views.length; i++) {
			MediaItem item = new MediaItem("o" + i, "VIDEO", "REELS", old, "c", "https://t", null, "p", 1, 10);
			mine.add(item);
			given(reader.readInsights(eq(item), any()))
				.willReturn(new MediaInsights(views[i], null, null, null, null, null, 50.0, null));
		}
		given(reader.readAllMedia(eq("100"), any())).willReturn(mine);
		referenceRepository.save(new ReferenceAccount("100", "ref.a", "900", 1, old));
		List<ReferenceMedia> refMedia = new ArrayList<>();
		int[] refViews = { 100_000, 120_000, 90_000, 400_000, 110_000 };
		for (int i = 0; i < refViews.length; i++) {
			refMedia.add(new ReferenceMedia(new MediaItem("x" + i, "VIDEO", "REELS", old, "c", "https://t", null, "p",
					null, 5), refViews[i]));
		}
		given(referenceReader.read(eq("100"), eq("ref.a"), anyInt(), any()))
			.willReturn(new ReferenceAccountData(new ReferenceProfile("900", "ref.a", 1, 1), refMedia));

		Long id = analysisService.request("100").id();
		OutlierService.AnalysisOutliers result = outlierService.compute(id);

		assertThat(result.own().outliers().medianViews()).isEqualTo(1100);
		assertThat(result.own().outliers().outliers()).extracting(OutlierCalculator.ScoredPost::mediaId)
			.containsExactly("o4");
		assertThat(result.references()).singleElement().satisfies(r -> {
			assertThat(r.username()).isEqualTo("ref.a");
			assertThat(r.outliers().medianViews()).isEqualTo(110_000);
			assertThat(r.outliers().outliers()).extracting(OutlierCalculator.ScoredPost::mediaId)
				.containsExactly("x3");
		});
		mockMvc.perform(get("/api/analyses/" + id + "/outliers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.own.outliers.medianViews").value(1100.0));
	}

	@Test
	void 실패한_분석의_아웃라이어는_409() throws Exception {
		given(reader.readAllMedia(eq("100"), any()))
			.willThrow(new GraphApiException(GraphApiErrorType.TOKEN_INVALID, 400, 190, 464, "x"));
		Long id = analysisService.request("100").id();

		mockMvc.perform(get("/api/analyses/" + id + "/outliers")).andExpect(status().isConflict());
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
	void 같은_계정의_분석이_진행_중이면_새_요청을_거절한다() {
		given(reader.readAllMedia(eq("100"), any())).willAnswer(invocation -> {
			// 첫 분석이 수집하는 도중에 같은 계정으로 다시 요청
			assertThatThrownBy(() -> analysisService.request("100")).isInstanceOf(AnalysisInProgressException.class);
			return List.of(reel, feed);
		});

		AnalysisResponse first = analysisService.request("100");

		assertThat(first.status()).isEqualTo(AnalysisStatus.DONE);
		// 끝난 뒤에는 다시 요청할 수 있다
		assertThat(analysisService.request("100").status()).isEqualTo(AnalysisStatus.DONE);
	}

	@Test
	void 서버가_수집_도중_꺼져_남은_분석은_시작할_때_실패로_정리한다() {
		Analysis stuck = writer.start("100", Instant.parse("2026-11-28T00:00:00Z"));

		analysisService.cleanUpAbandonedAnalyses();

		AnalysisResponse response = analysisService.get(stuck.getId());
		assertThat(response.status()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(response.failureType()).isEqualTo(AnalysisWriter.ABANDONED);
	}

	@Test
	void 예상하지_못한_오류여도_분석은_수집_중으로_남지_않는다() {
		given(reader.readAllMedia(eq("100"), any())).willThrow(new IllegalArgumentException("버그"));

		assertThatThrownBy(() -> analysisService.request("100")).isInstanceOf(IllegalArgumentException.class);

		assertThat(analysisRepository.findByStatus(AnalysisStatus.COLLECTING)).isEmpty();
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
