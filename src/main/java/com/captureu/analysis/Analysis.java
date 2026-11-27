package com.captureu.analysis;

import java.time.Duration;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

/** 분석 요청 한 건. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Analysis {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 32)
	private String igUserId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private AnalysisStatus status;

	@Column(nullable = false)
	private Instant requestedAt;

	private Instant finishedAt;

	private int mediaCount;

	private int insightFailedCount;

	private int apiCallCount;

	private Long durationMs;

	@Column(length = 30)
	private String failureType;

	@Column(length = 500)
	private String failureMessage;

	public static Analysis start(String igUserId, Instant now) {
		Analysis analysis = new Analysis();
		analysis.igUserId = igUserId;
		analysis.status = AnalysisStatus.COLLECTING;
		analysis.requestedAt = now;
		return analysis;
	}

	void complete(int mediaCount, int insightFailedCount, int apiCallCount, Instant now) {
		this.status = AnalysisStatus.DONE;
		this.mediaCount = mediaCount;
		this.insightFailedCount = insightFailedCount;
		finish(apiCallCount, now);
	}

	void fail(String failureType, String failureMessage, int apiCallCount, Instant now) {
		this.status = AnalysisStatus.FAILED;
		this.failureType = failureType;
		this.failureMessage = failureMessage == null || failureMessage.length() <= 500 ? failureMessage
				: failureMessage.substring(0, 500);
		finish(apiCallCount, now);
	}

	private void finish(int apiCallCount, Instant now) {
		this.apiCallCount = apiCallCount;
		this.finishedAt = now;
		this.durationMs = Duration.between(requestedAt, now).toMillis();
	}
}
