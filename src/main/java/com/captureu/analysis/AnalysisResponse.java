package com.captureu.analysis;

import java.time.Instant;
import java.util.List;

/**
 * 분석 요청 결과 요약. 아웃라이어·비교 리포트는 #8, #10에서 붙인다.
 *
 * @param mediaCount        내 계정 게시물 수
 * @param mediaTruncated    게시물이 너무 많아 끝까지 받지 못했는지
 * @param reelsWithSkipRate 스킵률까지 받은 릴스 수
 * @param references        참고 계정별 수집 결과
 */
public record AnalysisResponse(Long id, AnalysisStatus status, String igUserId, String username,
		Integer followersCount, Instant requestedAt, Instant finishedAt, int mediaCount, boolean mediaTruncated,
		int insightFailedCount, long reelsWithSkipRate, List<ReferenceResult> references, int apiCallCount,
		Long durationMs, String failureType, String failureMessage) {

	static AnalysisResponse of(Analysis a, AccountSnapshot account, long reelsWithSkipRate,
			List<AnalysisReference> references) {
		return new AnalysisResponse(a.getId(), a.getStatus(), a.getIgUserId(),
				account == null ? null : account.getUsername(), account == null ? null : account.getFollowersCount(),
				a.getRequestedAt(), a.getFinishedAt(), a.getMediaCount(), a.isMediaTruncated(),
				a.getInsightFailedCount(), reelsWithSkipRate, references.stream().map(ReferenceResult::of).toList(),
				a.getApiCallCount(), a.getDurationMs(), a.getFailureType(), a.getFailureMessage());
	}

	/** @param apiCallCount, durationMs 이 참고 계정을 수집하는 데 쓴 외부 호출 수와 시간 */
	public record ReferenceResult(String username, AnalysisStatus status, Integer followersCount, int mediaCount,
			int apiCallCount, Long durationMs, String failureType) {

		static ReferenceResult of(AnalysisReference r) {
			return new ReferenceResult(r.getUsername(), r.getStatus(), r.getFollowersCount(), r.getMediaCount(),
					r.getApiCallCount(), r.getDurationMs(), r.getFailureType());
		}
	}
}
