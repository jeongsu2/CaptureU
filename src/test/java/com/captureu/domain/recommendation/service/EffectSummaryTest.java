package com.captureu.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.captureu.domain.analysis.service.OwnPostsService.OwnPost;
import com.captureu.domain.analysis.util.CaptionHook.HookType;
import com.captureu.domain.recommendation.dto.RecommendationDraft.HookCandidate;
import com.captureu.domain.recommendation.entity.Recommendation;
import com.captureu.domain.recommendation.entity.RecommendationAdoption;
import com.captureu.domain.recommendation.service.EffectService.AdoptionEffect;
import com.captureu.domain.recommendation.service.EffectService.Assignment;
import com.captureu.domain.recommendation.service.EffectService.Baseline;
import com.captureu.domain.recommendation.service.EffectService.EffectSummary;
import com.captureu.domain.recommendation.service.EffectService.ExcludedReason;
import com.captureu.domain.recommendation.service.EffectService.MatchedPost;

class EffectSummaryTest {

	private final Baseline baseline = new Baseline(25000.0, 63.75, 0.46, 20);
	private static final Instant ADOPTED = Instant.parse("2026-11-01T00:00:00Z");

	private static OwnPost post(String id, int ageHours, int views, Double skipRate, int saved) {
		return new OwnPost(id, ADOPTED.plusSeconds(3600), "첫 줄", "첫 줄", null, null, true, ageHours, views, skipRate,
				saved);
	}

	private static OwnPost captioned(String id, String caption, boolean reels, int ageHours) {
		return new OwnPost(id, ADOPTED.plusSeconds(3600), caption, caption, null, null, reels, ageHours, 1000, 50.0,
				10);
	}

	private static AdoptionEffect effect(MatchedPost post) {
		return new AdoptionEffect(1L, 1L, "첫 줄", HookType.QUESTION, Instant.now(), "AUTO", post, null);
	}

	private static RecommendationAdoption adoption(long id, String text, Instant adoptedAt) {
		Recommendation r = Recommendation.of(1L, "주제", Recommendation.Generator.TEMPLATE, null, "template-v1", null,
				null, (BigDecimal) null, 0, null, "{}", adoptedAt);
		ReflectionTestUtils.setField(r, "id", 1L);
		RecommendationAdoption a = RecommendationAdoption.of(r, 0, new HookCandidate(text, HookType.OTHER, null),
				adoptedAt);
		ReflectionTestUtils.setField(a, "id", id);
		return a;
	}

	@Test
	void 게시_7일이_지난_연결_릴스만_평균에_넣고_뺀_이유를_나눠_적는다() {
		MatchedPost a = MatchedPost.of(post("a", 200, 50000, 55.0, 500), baseline, 1.0);
		MatchedPost b = MatchedPost.of(post("b", 300, 25000, 60.0, 250), baseline, 0.8);
		MatchedPost c = MatchedPost.of(post("c", 400, 12500, 62.5, 50), baseline, 0.7);
		MatchedPost young = MatchedPost.of(post("d", 20, 1000, 10.0, 0), baseline, 1.0);
		MatchedPost feed = MatchedPost.of(captioned("e", "피드", false, 900), baseline, 1.0);

		EffectSummary summary = EffectService.summarize(List.of(effect(a), effect(b), effect(c), effect(young),
				effect(feed), effect(MatchedPost.missing("gone", 1.0)),
				new AdoptionEffect(2L, 1L, "안 쓴 문구", HookType.LIST, Instant.now(), "NOT_FOUND", null, null)),
				baseline);

		assertThat(a.skipRateDiff()).isEqualTo(-8.75);
		assertThat(a.viewsMultiple()).isEqualTo(2.0);
		assertThat(young.excludedReason()).isEqualTo(ExcludedReason.YOUNG);
		assertThat(feed.excludedReason()).isEqualTo(ExcludedReason.NOT_REELS);
		assertThat(summary.matchedCount()).isEqualTo(6);
		assertThat(summary.comparedCount()).isEqualTo(3);
		// (-8.75 + -3.75 + -1.25) / 3
		assertThat(summary.avgSkipRateDiff()).isEqualTo(-4.58);
		assertThat(summary.medianViewsMultiple()).isEqualTo(1.0);
		assertThat(summary.lowSample()).isFalse();
		assertThat(summary.headline()).isEqualTo("추천을 쓴 게시물 3개의 스킵률이 내 중앙값(63.75%)보다 평균 4.58%p 낮아요");
		assertThat(summary.notes()).anyMatch(n -> n.contains("단정할 수는 없어요"))
			.anyMatch(n -> n.contains("7일이 안 된 게시물 1개"))
			.anyMatch(n -> n.contains("릴스가 아닌 게시물 1개"))
			.anyMatch(n -> n.contains("지표를 받지 못한 게시물 1개"));
	}

	@Test
	void 헤드라인의_개수는_실제로_평균에_넣은_게시물_수다() {
		MatchedPost withSkip = MatchedPost.of(post("a", 200, 50000, 70.0, 500), baseline, 1.0);
		MatchedPost noSkip1 = MatchedPost.of(post("b", 200, 50000, null, 500), baseline, 1.0);
		MatchedPost noSkip2 = MatchedPost.of(post("c", 200, 50000, null, 500), baseline, 1.0);

		EffectSummary summary = EffectService.summarize(List.of(effect(withSkip), effect(noSkip1), effect(noSkip2)),
				baseline);

		assertThat(summary.comparedCount()).isEqualTo(1);
		assertThat(summary.lowSample()).isTrue();
		assertThat(summary.headline()).startsWith("추천을 쓴 게시물 1개의").endsWith("6.25%p 높아요");
		assertThat(EffectService.summarize(List.of(), baseline).headline()).isNull();
	}

	@Test
	void 기준값은_추천을_쓴_게시물을_빼고_낸다() {
		List<OwnPost> posts = List.of(post("used", 300, 90000, 30.0, 900), post("x", 300, 10000, 60.0, 10),
				post("y", 300, 20000, 70.0, 20), post("z", 300, 30000, 80.0, 30), post("young", 10, 1, 1.0, 0));

		Baseline b = EffectService.baseline(posts, Set.of("used"));

		assertThat(b.medianViews()).isEqualTo(20000.0);
		assertThat(b.medianSkipRate()).isEqualTo(70.0);
		assertThat(b.count()).isEqualTo(3);
	}

	@Test
	void 점수_높은_쌍부터_짝지어_먼저_채택한_문구가_더_잘_맞는_게시물을_뺏지_않는다() {
		RecommendationAdoption early = adoption(1, "겨울 출근룩 아직도 이렇게 입으세요", ADOPTED);
		RecommendationAdoption late = adoption(2, "겨울 출근룩, 아직도 이렇게 하세요?", ADOPTED.plusSeconds(60));
		OwnPost p = captioned("p", "겨울 출근룩, 아직도 이렇게 하세요? 🧣", true, 200);

		Assignment result = EffectService.assign(List.of(early, late), List.of(p), Map.of());

		assertThat(result.links().get(2L).mediaId()).isEqualTo("p");
		assertThat(result.links()).doesNotContainKey(1L);
		// 이미 짝지어진 게시물은 다른 채택의 확인 후보로도 내지 않는다
		assertThat(result.asks()).isEmpty();
	}

	@Test
	void 뗀_게시물은_몇_개든_다시_연결하지_않는다() {
		RecommendationAdoption a = adoption(1, "겨울 출근룩, 아직도 이렇게 하세요?", ADOPTED);
		OwnPost p1 = captioned("p1", "겨울 출근룩, 아직도 이렇게 하세요?", true, 200);
		OwnPost p2 = captioned("p2", "겨울 출근룩 아직도 이렇게 하세요!!", true, 200);

		Assignment result = EffectService.assign(List.of(a), List.of(p1, p2), Map.of(1L, Set.of("p1", "p2")));

		assertThat(result.links()).isEmpty();
		assertThat(result.asks()).isEmpty();
	}
}
