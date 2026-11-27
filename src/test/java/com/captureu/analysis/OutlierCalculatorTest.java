package com.captureu.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.captureu.analysis.OutlierCalculator.AccountOutliers;
import com.captureu.analysis.OutlierCalculator.PostMetric;
import com.captureu.analysis.OutlierCalculator.ScoredPost;

class OutlierCalculatorTest {

	private static final int MATURE = OutlierCalculator.MATURE_AGE_HOURS;

	@Test
	void 중앙값_대비_2배_이상이_아웃라이어다() {
		List<PostMetric> posts = List.of(reel("a", 1000), reel("b", 2000), reel("c", 3000), reel("d", 4000),
				reel("e", 9000));

		AccountOutliers result = OutlierCalculator.calculate(posts);

		assertThat(result.medianViews()).isEqualTo(3000);
		assertThat(result.outliers()).extracting(ScoredPost::mediaId).containsExactly("e");
		assertThat(result.outliers().get(0).multiple()).isEqualTo(3.0);
		assertThat(result.posts()).extracting(ScoredPost::mediaId).startsWith("e", "d");
		assertThat(result.lowSample()).isFalse();
	}

	@Test
	void 바이럴_하나가_있으면_평균은_끌려_올라가지만_중앙값은_그대로다() {
		List<PostMetric> posts = new ArrayList<>();
		for (int i = 0; i < 9; i++) {
			posts.add(reel("p" + i, 10_000));
		}
		posts.add(reel("viral", 1_000_000));

		AccountOutliers result = OutlierCalculator.calculate(posts);

		assertThat(result.medianViews()).isEqualTo(10_000);
		assertThat(result.meanViews()).isEqualTo(109_000);
		// 평균 기준이었다면 나머지 9개가 모두 '평균의 0.09배'가 된다. 중앙값 기준이면 1배다
		assertThat(result.posts()).filteredOn(p -> !p.outlier()).allSatisfy(p -> assertThat(p.multiple()).isEqualTo(1.0));
		assertThat(result.outliers()).extracting(ScoredPost::mediaId).containsExactly("viral");
	}

	@Test
	void 게시_7일_미만은_판정하지_않고_따로_모은다() {
		List<PostMetric> posts = List.of(reel("old1", 1000), reel("old2", 1000), reel("old3", 1000),
				reel("old4", 1000), reel("old5", 1000), new PostMetric("new", "REELS", 30, 50_000, 10));

		AccountOutliers result = OutlierCalculator.calculate(posts);

		assertThat(result.judgedCount()).isEqualTo(5);
		assertThat(result.youngMediaIds()).containsExactly("new");
		assertThat(result.outliers()).isEmpty();
	}

	@Test
	void 피드와_조회수_없는_게시물은_빼고_적으면_참고용으로_표시한다() {
		List<PostMetric> posts = List.of(reel("a", 1000), reel("b", 3000),
				new PostMetric("feed", "FEED", MATURE, 99_999, 1), new PostMetric("noViews", "REELS", MATURE, null, 1));

		AccountOutliers result = OutlierCalculator.calculate(posts);

		assertThat(result.judgedCount()).isEqualTo(2);
		assertThat(result.excludedCount()).isEqualTo(1);
		assertThat(result.medianViews()).isEqualTo(2000);
		assertThat(result.lowSample()).isTrue();
	}

	@Test
	void 중앙값이_0이면_배수_없이_조회수가_있는_게시물을_아웃라이어로_본다() {
		List<PostMetric> posts = List.of(reel("a", 0), reel("b", 0), reel("c", 0), reel("d", 5000), reel("e", 12000));

		AccountOutliers result = OutlierCalculator.calculate(posts);

		assertThat(result.medianViews()).isEqualTo(0);
		assertThat(result.outliers()).extracting(ScoredPost::mediaId).containsExactly("e", "d");
		assertThat(result.posts()).allSatisfy(p -> assertThat(p.multiple()).isNull());
	}

	@Test
	void 반올림한_배수로_판정해서_화면의_숫자와_판정이_같다() {
		// 중앙값 1000에서 1996은 1.996배 → 화면에 2.0으로 보이므로 아웃라이어여야 한다
		List<PostMetric> posts = List.of(reel("a", 900), reel("b", 950), reel("c", 1000), reel("d", 1050),
				reel("e", 1996));

		AccountOutliers result = OutlierCalculator.calculate(posts);

		assertThat(result.outliers()).singleElement().satisfies(p -> {
			assertThat(p.mediaId()).isEqualTo("e");
			assertThat(p.multiple()).isEqualTo(2.0);
		});
	}

	@Test
	void 배수가_같으면_조회수_높은_순_그다음_ID_순으로_정렬한다() {
		List<PostMetric> posts = List.of(reel("b", 1000), reel("a", 1000), reel("c", 1000));

		assertThat(OutlierCalculator.calculate(posts).posts()).extracting(ScoredPost::mediaId)
			.containsExactly("a", "b", "c");
	}

	@Test
	void 평균도_소수_둘째_자리로_반올림한다() {
		AccountOutliers result = OutlierCalculator.calculate(List.of(reel("a", 1000), reel("b", 1000), reel("c", 1100)));

		assertThat(result.meanViews()).isEqualTo(1033.33);
	}

	@Test
	void 판정할_게시물이_없으면_빈_결과() {
		AccountOutliers result = OutlierCalculator.calculate(List.of(new PostMetric("new", "REELS", 1, 10, 1)));

		assertThat(result.medianViews()).isNull();
		assertThat(result.posts()).isEmpty();
		assertThat(result.youngMediaIds()).containsExactly("new");
		assertThat(result.lowSample()).isTrue();
	}

	@Test
	void 댓글_반응률은_조회수_대비_댓글_수의_퍼센트다() {
		AccountOutliers result = OutlierCalculator.calculate(List.of(new PostMetric("a", "REELS", MATURE, 2000, 50)));

		assertThat(result.posts().get(0).commentRate()).isEqualTo(2.5);
	}

	private static PostMetric reel(String id, int views) {
		return new PostMetric(id, "REELS", MATURE + 1, views, 10);
	}
}
