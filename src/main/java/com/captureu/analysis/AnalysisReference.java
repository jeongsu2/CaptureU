package com.captureu.analysis;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.captureu.common.Texts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 분석 요청별 참고 계정 수집 결과. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisReference {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long analysisId;

	@Column(nullable = false, length = 30)
	private String username;

	@Column(length = 32)
	private String igId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private AnalysisStatus status;

	private Integer followersCount;

	private int mediaCount;

	/** 이 참고 계정을 수집하는 데 쓴 외부 호출 수와 시간. 공유 캐시(#9)의 전후 비교에 쓴다. */
	private int apiCallCount;

	private Long durationMs;

	/** 공유 캐시에서 가져왔는지 (#9). */
	private boolean cacheHit;

	@Column(length = 30)
	private String failureType;

	@Column(length = 500)
	private String failureMessage;

	static AnalysisReference done(Long analysisId, String username, String igId, Integer followersCount,
			int mediaCount, int apiCallCount, long durationMs, boolean cacheHit) {
		AnalysisReference r = new AnalysisReference();
		r.analysisId = analysisId;
		r.username = username;
		r.igId = igId;
		r.status = AnalysisStatus.DONE;
		r.followersCount = followersCount;
		r.mediaCount = mediaCount;
		r.apiCallCount = apiCallCount;
		r.durationMs = durationMs;
		r.cacheHit = cacheHit;
		return r;
	}

	static AnalysisReference failed(Long analysisId, String username, String failureType, String failureMessage,
			int apiCallCount, long durationMs, boolean cacheHit) {
		AnalysisReference r = new AnalysisReference();
		r.analysisId = analysisId;
		r.username = username;
		r.status = AnalysisStatus.FAILED;
		r.failureType = failureType;
		r.failureMessage = Texts.truncate(failureMessage, 500);
		r.apiCallCount = apiCallCount;
		r.durationMs = durationMs;
		r.cacheHit = cacheHit;
		return r;
	}
}
