package com.captureu.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.captureu.analysis.CaptionHook.HookType;
import com.captureu.analysis.OwnPostsService.OwnPost;
import com.captureu.recommendation.EffectService.AdoptionEffect;
import com.captureu.recommendation.EffectService.Baseline;
import com.captureu.recommendation.EffectService.EffectSummary;
import com.captureu.recommendation.EffectService.MatchedPost;

class EffectSummaryTest {

	private final Baseline baseline = new Baseline(25000.0, 63.75, 0.46);

	private static OwnPost post(String id, int ageHours, int views, double skipRate, int saved) {
		return new OwnPost(id, Instant.parse("2026-11-01T00:00:00Z"), "첫 줄", "첫 줄", null, null, true, ageHours,
				views, skipRate, saved);
	}

	private static AdoptionEffect effect(MatchedPost post) {
		return new AdoptionEffect(1L, 1L, "첫 줄", HookType.QUESTION, Instant.now(), "AUTO", post, null);
	}

	@Test
	void 게시_7일이_지난_연결_게시물만_평균에_넣고_중앙값과의_차이를_보여_준다() {
		MatchedPost a = MatchedPost.of(post("a", 200, 50000, 55.0, 500), baseline, 1.0);
		MatchedPost b = MatchedPost.of(post("b", 300, 25000, 60.0, 250), baseline, 0.8);
		MatchedPost c = MatchedPost.of(post("c", 400, 12500, 62.5, 50), baseline, 0.7);
		MatchedPost young = MatchedPost.of(post("d", 20, 1000, 10.0, 0), baseline, 1.0);

		EffectSummary summary = EffectService.summarize(List.of(effect(a), effect(b), effect(c), effect(young),
				new AdoptionEffect(2L, 1L, "안 쓴 문구", HookType.LIST, Instant.now(), "NOT_FOUND", null, null)),
				baseline);

		assertThat(a.skipRateDiff()).isEqualTo(-8.75);
		assertThat(a.viewsMultiple()).isEqualTo(2.0);
		assertThat(a.saveRate()).isEqualTo(1.0);
		assertThat(young.mature()).isFalse();
		assertThat(summary.adoptedCount()).isEqualTo(5);
		assertThat(summary.matchedCount()).isEqualTo(4);
		assertThat(summary.matureCount()).isEqualTo(3);
		// (-8.75 + -3.75 + -1.25) / 3
		assertThat(summary.avgSkipRateDiff()).isEqualTo(-4.58);
		assertThat(summary.medianViewsMultiple()).isEqualTo(1.0);
		assertThat(summary.lowSample()).isFalse();
		assertThat(summary.headline()).isEqualTo("추천을 쓴 게시물 3개의 스킵률이 내 중앙값(63.75%)보다 평균 4.58%p 낮아요");
		assertThat(summary.notes()).anyMatch(n -> n.contains("단정할 수는 없어요"))
			.anyMatch(n -> n.contains("7일이 안 된 게시물 1개"));
	}

	@Test
	void 비교할_게시물이_적으면_참고용이고_없으면_결론을_내지_않는다() {
		MatchedPost a = MatchedPost.of(post("a", 200, 50000, 70.0, 500), baseline, 1.0);

		EffectSummary one = EffectService.summarize(List.of(effect(a)), baseline);
		EffectSummary none = EffectService.summarize(List.of(), baseline);

		assertThat(one.lowSample()).isTrue();
		assertThat(one.headline()).endsWith("6.25%p 높아요");
		assertThat(one.notes()).anyMatch(n -> n.contains("1개라 참고용"));
		assertThat(none.headline()).isNull();
	}
}
