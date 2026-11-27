package com.captureu.analysis;

import java.time.Instant;

/**
 * 분석 요청 결과 요약. 아웃라이어·비교 리포트는 #8, #10에서 붙인다.
 *
 * @param reelsWithSkipRate 스킵률까지 받은 릴스 수
 */
public record AnalysisResponse(Long id, AnalysisStatus status, String igUserId, String username,
		Integer followersCount, Instant requestedAt, Instant finishedAt, int mediaCount, int insightFailedCount,
		long reelsWithSkipRate, int apiCallCount, Long durationMs, String failureType, String failureMessage) {

	static AnalysisResponse of(Analysis a, AccountSnapshot account, long reelsWithSkipRate) {
		return new AnalysisResponse(a.getId(), a.getStatus(), a.getIgUserId(),
				account == null ? null : account.getUsername(), account == null ? null : account.getFollowersCount(),
				a.getRequestedAt(), a.getFinishedAt(), a.getMediaCount(), a.getInsightFailedCount(), reelsWithSkipRate,
				a.getApiCallCount(), a.getDurationMs(), a.getFailureType(), a.getFailureMessage());
	}
}
