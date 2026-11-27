package com.captureu.instagram.account;

import java.util.Map;

import com.captureu.common.Texts;

/**
 * 게시물 인사이트. 받지 못한 지표는 null. 하나도 받지 못하면 error에 이유를 남긴다.
 *
 * @param avgWatchTimeMs 릴스 평균 시청 시간(ms)
 * @param skipRate       릴스 스킵률(%). 첫 3초 안에 넘긴 비율
 */
public record MediaInsights(Integer views, Integer reach, Integer saved, Integer shares, Integer totalInteractions,
		Integer avgWatchTimeMs, Double skipRate, String error) {

	static final int MAX_ERROR_LENGTH = 200;

	static MediaInsights from(Map<String, Number> v) {
		if (v.isEmpty()) {
			return failed("빈 인사이트 응답");
		}
		return new MediaInsights(i(v.get("views")), i(v.get("reach")), i(v.get("saved")), i(v.get("shares")),
				i(v.get("total_interactions")), i(v.get("ig_reels_avg_watch_time")), d(v.get("reels_skip_rate")), null);
	}

	static MediaInsights failed(String error) {
		String reason = error == null || error.isBlank() ? "알 수 없는 오류" : error;
		return new MediaInsights(null, null, null, null, null, null, null, Texts.truncate(reason, MAX_ERROR_LENGTH));
	}

	public boolean isFailed() {
		return error != null;
	}

	private static Integer i(Number n) {
		return n == null ? null : (int) Math.min(n.longValue(), Integer.MAX_VALUE);
	}

	private static Double d(Number n) {
		return n == null ? null : n.doubleValue();
	}
}
