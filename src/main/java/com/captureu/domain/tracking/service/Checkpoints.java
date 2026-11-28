package com.captureu.domain.tracking.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.captureu.domain.analysis.service.OutlierCalculator;

/** 게시물 나이별 수집 시점과 같은 나이끼리 비교하는 계산 (#5). */
public final class Checkpoints {

	/** 게시 후 몇 시간에 잴지. 72시간 뒤로는 7일까지 하루 1번. */
	public static final List<Integer> HOURS = List.of(1, 3, 6, 12, 24, 48, 72, 96, 120, 144, 168);
	/** 비교하려면 과거 게시물이 이만큼 있어야 한다. */
	public static final int MIN_BASELINE = 3;

	private Checkpoints() {
	}

	/**
	 * 지금 재야 하는 나이. 나이가 지난 직후부터 허용 시간 안에만 잰다 (너무 늦게 잰 값은 같은 나이로 비교할 수 없다).
	 * 허용 시간은 나이의 절반, 최소 30분·최대 12시간. 예: 1시간은 1:00~1:30, 24시간은 24:00~36:00.
	 */
	public static Optional<Integer> due(Instant postedAt, Instant now, Set<Integer> done) {
		for (int hours : HOURS) {
			Instant start = postedAt.plus(Duration.ofHours(hours));
			Instant end = start.plus(tolerance(hours));
			if (!now.isBefore(start) && now.isBefore(end) && !done.contains(hours)) {
				return Optional.of(hours);
			}
		}
		return Optional.empty();
	}

	static Duration tolerance(int hours) {
		long minutes = Math.min(Math.max(hours * 30L, 30), 12 * 60);
		return Duration.ofMinutes(minutes);
	}

	/** 마지막 나이가 지나고 허용 시간도 끝났으면 더 잴 게 없다. */
	public static boolean finished(Instant postedAt, Instant now) {
		int last = HOURS.get(HOURS.size() - 1);
		return !now.isBefore(postedAt.plus(Duration.ofHours(last)).plus(tolerance(last)));
	}

	/** 같은 나이 과거 값과의 비교. 과거 값이 {@value #MIN_BASELINE}개보다 적으면 baseline이 null. */
	public record Comparison(Double baseline, int baselineCount, Double multiple, boolean surge) {
	}

	public static Comparison compare(Integer views, List<Integer> pastViews) {
		List<Double> past = new ArrayList<>();
		for (Integer v : pastViews) {
			if (v != null) {
				past.add(v.doubleValue());
			}
		}
		if (past.size() < MIN_BASELINE) {
			return new Comparison(null, past.size(), null, false);
		}
		double baseline = OutlierCalculator.median(past);
		if (views == null || baseline <= 0) {
			return new Comparison(baseline, past.size(), null, false);
		}
		double multiple = Math.round(views / baseline * 10) / 10.0;
		return new Comparison(baseline, past.size(), multiple, multiple >= OutlierCalculator.OUTLIER_MULTIPLE);
	}
}
