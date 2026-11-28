package com.captureu.domain.thumbnail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.captureu.TestcontainersConfiguration;
import com.captureu.domain.analysis.service.AnalysisService;
import com.captureu.domain.instagram.client.InstagramAccountReader;
import com.captureu.domain.instagram.client.ReferenceAccountReader;
import com.captureu.domain.instagram.dto.AccountInfo;
import com.captureu.domain.instagram.dto.MediaInsights;
import com.captureu.domain.instagram.dto.MediaItem;
import com.captureu.domain.recommendation.service.RecommendationService;
import com.captureu.domain.thumbnail.client.ThumbnailImageFetcher;
import com.captureu.domain.thumbnail.client.ThumbnailImageFetcher.Image;
import com.captureu.domain.thumbnail.client.ThumbnailTagger;
import com.captureu.domain.thumbnail.client.ThumbnailTagger.Tagged;
import com.captureu.domain.thumbnail.dto.ThumbnailTags;
import com.captureu.domain.thumbnail.dto.ThumbnailTags.Composition;
import com.captureu.domain.thumbnail.dto.ThumbnailTags.TextSize;
import com.captureu.domain.thumbnail.dto.ThumbnailTags.Tone;
import com.captureu.domain.thumbnail.service.ThumbnailService.Feature;
import com.captureu.domain.thumbnail.service.ThumbnailService.TagResult;
import com.captureu.domain.thumbnail.service.ThumbnailService.TagStatus;

/** 썸네일을 받아 특징을 뽑고(같은 이미지는 한 번만), 아웃라이어와 비교하고, 추천 썸네일 구성안에 쓴다. 외부 호출은 가짜. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "captureu.instagram.graph.ig-user-id=100", "captureu.llm.anthropic.api-key=" })
@ActiveProfiles("test")
class ThumbnailIntegrationTest {

	@Autowired
	AnalysisService analysisService;
	@Autowired
	ThumbnailService thumbnailService;
	@Autowired
	RecommendationService recommendationService;
	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	InstagramAccountReader reader;
	@MockitoBean
	ReferenceAccountReader referenceReader;
	@MockitoBean
	ThumbnailTagger tagger;
	@MockitoBean
	ThumbnailImageFetcher fetcher;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from media_thumbnail");
		jdbc.update("delete from thumbnail_feature");
		jdbc.update("delete from reference_account");
		given(reader.readAccount(eq("100"), any())).willReturn(new AccountInfo("100", "tester", 100, 1));
		given(reader.readDemographics(eq("100"), any())).willReturn(List.of());
		given(tagger.promptVersion()).willReturn("thumb-v1");
	}

	/** 조회수 1000짜리 5개와 5000짜리 3개(아웃라이어). 아웃라이어 썸네일만 얼굴 클로즈업. 두 게시물은 같은 이미지. */
	private Long analyze() {
		List<MediaItem> media = new ArrayList<>();
		Instant old = Instant.now().minus(30, ChronoUnit.DAYS);
		for (int i = 0; i < 8; i++) {
			MediaItem m = new MediaItem("th-" + i, "VIDEO", "REELS", old.plusSeconds(i), "첫 줄 " + i,
					"https://scontent.cdninstagram.com/th-" + i + ".jpg", null, "https://p/" + i, 1, 1);
			media.add(m);
			int views = i < 5 ? 1000 : 5000;
			given(reader.readInsights(eq(m), any()))
				.willReturn(new MediaInsights(views, views, 1, 0, 1, views, 50.0, null));
			String content = i == 1 ? "image-0" : "image-" + i; // th-0과 th-1은 같은 이미지
			given(fetcher.fetch(m.coverImageUrl()))
				.willReturn(new Image(content.getBytes(StandardCharsets.UTF_8), "image/jpeg"));
		}
		given(reader.readAllMedia(eq("100"), any())).willReturn(media);
		return analysisService.request("100").id();
	}

	@Test
	void 썸네일을_한_번씩만_분석하고_아웃라이어와_비교해_추천_근거로_쓴다() {
		Long analysisId = analyze();
		given(tagger.enabled()).willReturn(true);
		given(tagger.tag(any())).willAnswer(inv -> {
			String content = new String(((Image) inv.getArgument(0)).bytes(), StandardCharsets.UTF_8);
			boolean outlier = Integer.parseInt(content.substring("image-".length())) >= 5;
			return new Tagged(new ThumbnailTags(outlier, 1, outlier ? TextSize.LARGE : TextSize.NONE, "", Tone.BRIGHT,
					Composition.CENTER_SUBJECT), "claude-opus-5-5", "thumb-v1", 1500, 120,
					new java.math.BigDecimal("0.008400"));
		});

		TagResult first = thumbnailService.tag(analysisId);

		assertThat(first.status()).isEqualTo(TagStatus.DONE);
		assertThat(first.selected()).isEqualTo(8);
		assertThat(first.tagged()).isEqualTo(7);
		assertThat(first.reused()).isEqualTo(1);
		assertThat(first.failed()).isZero();
		assertThat(first.costUsd()).isEqualByComparingTo("0.058800");
		verify(tagger, times(7)).tag(any());

		// 다시 불러도 이미 분석한 게시물은 건너뛴다
		TagResult second = thumbnailService.tag(analysisId);
		assertThat(second.alreadyTagged()).isEqualTo(8);
		assertThat(second.tagged()).isZero();
		verify(tagger, times(7)).tag(any());

		var comparison = thumbnailService.compare(analysisId);
		var own = comparison.accounts().get(0);
		assertThat(own.outlierTagged()).isEqualTo(3);
		assertThat(own.otherTagged()).isEqualTo(5);
		assertThat(comparison.signals()).extracting(ThumbnailService.ThumbnailSignal::feature)
			.contains(Feature.FACE_CLOSE_UP, Feature.LARGE_TEXT);

		var rec = recommendationService.recommend(analysisId, "겨울 출근룩");
		assertThat(rec.result().thumbnailSignals()).isNotEmpty();
		assertThat(rec.result().thumbnail().layout()).contains("얼굴을 화면의 1/3 이상");
		assertThat(rec.result().thumbnail().rationale()).contains("내 계정(잘된 썸네일 100% vs 나머지 0%)");
	}

	@Test
	void 버전이_다른_결과는_다시_분석해_덮어쓰고_비교에_섞지_않는다() {
		Long analysisId = analyze();
		given(tagger.enabled()).willReturn(true);
		given(tagger.promptVersion()).willReturn("thumb-v1-mock");
		given(tagger.tag(any())).willAnswer(inv -> new Tagged(new ThumbnailTags(false, 0, TextSize.NONE, "",
				Tone.DARK, Composition.SCENE), "claude-mock", "thumb-v1-mock", 10, 10, null));
		assertThat(thumbnailService.tag(analysisId).tagged()).isEqualTo(7);

		// 실제 분석으로 바꾸면 목 결과는 없는 것으로 보고 다시 분석한다
		given(tagger.promptVersion()).willReturn("thumb-v1");
		assertThat(thumbnailService.compare(analysisId).accounts().get(0).outlierTagged()).isZero();
		given(tagger.tag(any())).willAnswer(inv -> new Tagged(new ThumbnailTags(true, 1, TextSize.LARGE, "",
				Tone.BRIGHT, Composition.CENTER_SUBJECT), "claude-opus-5-5", "thumb-v1", 1500, 120,
				new java.math.BigDecimal("0.008400")));

		TagResult real = thumbnailService.tag(analysisId);

		assertThat(real.alreadyTagged()).isZero();
		assertThat(real.tagged()).isEqualTo(7);
		assertThat(real.reused()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from thumbnail_feature where prompt_version = 'thumb-v1-mock'",
				Integer.class)).isZero();
		assertThat(thumbnailService.compare(analysisId).accounts().get(0).outlierTagged()).isEqualTo(3);
	}

	@Test
	void 키가_없으면_이미지를_받지도_분석하지도_않는다() {
		Long analysisId = analyze();
		given(tagger.enabled()).willReturn(false);

		TagResult result = thumbnailService.tag(analysisId);

		assertThat(result.status()).isEqualTo(TagStatus.SKIPPED_NO_API_KEY);
		verify(fetcher, never()).fetch(any());
		verify(tagger, never()).tag(any());
		assertThat(thumbnailService.compare(analysisId).signals()).isEmpty();
	}
}
