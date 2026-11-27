package com.captureu.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 계정 안에서 평소보다 유독 잘된 게시물(아웃라이어)을 찾는다.
 * <ul>
 * <li>기준은 조회수의 <b>중앙값</b> 대비 배수다. 평균은 바이럴 게시물 하나에 끌려 올라가서 나머지가 모두 '평균 이하'가 된다.</li>
 * <li>게시 7일 미만 게시물은 조회수가 아직 쌓이는 중이라 판정하지 않는다 (초반 급상승 감지는 '더 이어서 하기' #5).</li>
 * <li>릴스만 본다. 참고 계정의 피드는 조회수가 오지 않고, 릴스와 피드는 조회수 규모가 달라서 섞으면 기준이 흔들린다.</li>
 * </ul>
 * 계정 규모가 달라도 배수로 비교하므로 팔로워 2만 계정과 14만 계정을 같은 기준으로 볼 수 있다.
 */
public final class OutlierCalculator {

	public static final int MATURE_AGE_HOURS = 7 * 24;
	public static final double OUTLIER_MULTIPLE = 2.0;
	/** 판정할 게시물이 이보다 적으면 중앙값을 믿기 어려워 '참고용'으로 표시한다. */
	public static final int MIN_SAMPLE = 5;

	/** 배수 높은 순, 같으면 조회수 높은 순, 그래도 같으면 ID 순 (요청할 때마다 순서가 같도록). */
	private static final Comparator<ScoredPost> ORDER = Comparator
		.comparing(ScoredPost::multiple, Comparator.nullsLast(Comparator.reverseOrder()))
		.thenComparing(ScoredPost::views, Comparator.reverseOrder())
		.thenComparing(ScoredPost::mediaId);

	private OutlierCalculator() {
	}

	public static AccountOutliers calculate(List<PostMetric> posts) {
		List<PostMetric> reels = posts.stream().filter(PostMetric::isReels).toList();
		List<PostMetric> young = reels.stream().filter(p -> p.ageHours() < MATURE_AGE_HOURS).toList();
		List<PostMetric> mature = reels.stream().filter(p -> p.ageHours() >= MATURE_AGE_HOURS).toList();
		List<PostMetric> judged = mature.stream().filter(p -> p.views() != null).toList();
		int excluded = mature.size() - judged.size();
		List<String> youngIds = young.stream().map(PostMetric::mediaId).toList();

		if (judged.isEmpty()) {
			return new AccountOutliers(0, excluded, null, null, List.of(), youngIds, true);
		}
		double median = median(judged.stream().map(p -> p.views().doubleValue()).toList());
		double mean = judged.stream().mapToInt(PostMetric::views).average().orElse(0);

		List<ScoredPost> scored = new ArrayList<>();
		for (PostMetric p : judged) {
			// 중앙값이 0이면 배수를 정할 수 없다. 조회수가 있는 게시물은 모두 평소보다 잘된 것으로 본다
			Double multiple = median == 0 ? null : round2(p.views() / median);
			boolean outlier = multiple == null ? p.views() > 0 : multiple >= OUTLIER_MULTIPLE;
			scored.add(new ScoredPost(p.mediaId(), p.views(), multiple, percent(p.comments(), p.views()), outlier));
		}
		scored.sort(ORDER);
		return new AccountOutliers(judged.size(), excluded, round2(median), round2(mean), scored, youngIds,
				judged.size() < MIN_SAMPLE);
	}

	public static double median(List<Double> values) {
		List<Double> sorted = values.stream().sorted().toList();
		int n = sorted.size();
		if (n == 0) {
			throw new IllegalArgumentException("빈 목록의 중앙값");
		}
		return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
	}

	/** part / whole을 %로, 소수 둘째 자리까지. 나눌 수 없으면 null. */
	static Double percent(Integer part, Integer whole) {
		if (part == null || whole == null || whole == 0) {
			return null;
		}
		return round2(100.0 * part / whole);
	}

	static double round2(double v) {
		return Math.round(v * 100) / 100.0;
	}

	/** 계산에 쓰는 게시물 하나. views는 내 계정은 인사이트, 참고 계정은 공개 조회수. */
	public record PostMetric(String mediaId, String productType, int ageHours, Integer views, Integer comments) {

		boolean isReels() {
			return "REELS".equals(productType);
		}
	}

	/**
	 * @param multiple    중앙값 대비 배수 (소수 둘째 자리). 중앙값이 0이면 null. 아웃라이어 판정도 이 반올림 값으로 한다
	 * @param commentRate 조회수 대비 댓글 비율(%). 댓글 이벤트 게시물은 높게 나오므로 해석에 주의
	 */
	public record ScoredPost(String mediaId, int views, Double multiple, Double commentRate, boolean outlier) {
	}

	/**
	 * @param judgedCount   판정한 릴스 수 (게시 7일 이상, 조회수 있음)
	 * @param excludedCount 게시 7일 이상인데 조회수가 없어 판정에서 뺀 릴스 수 (인사이트 실패 등)
	 * @param medianViews   판정한 릴스의 조회수 중앙값
	 * @param meanViews     같은 릴스의 평균 (중앙값과 비교용)
	 * @param posts         배수 높은 순. outlier=true가 아웃라이어
	 * @param youngMediaIds 게시 7일 미만이라 판정하지 않은 릴스
	 * @param lowSample     판정한 릴스가 너무 적어 참고용
	 */
	public record AccountOutliers(int judgedCount, int excludedCount, Double medianViews, Double meanViews,
			List<ScoredPost> posts, List<String> youngMediaIds, boolean lowSample) {

		public List<ScoredPost> outliers() {
			return posts.stream().filter(ScoredPost::outlier).toList();
		}
	}
}
