package com.captureu.domain.recommendation.entity;

import java.math.BigDecimal;
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

/** 추천 한 번의 결과와 생성 비용 (#13). 결과 본문은 JSON으로 통째로 저장한다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recommendation {

	public enum Generator {
		/** Claude로 생성 */
		LLM,
		/** 규칙 기반 템플릿 (API 키가 없거나 LLM 호출이 실패했을 때) */
		TEMPLATE
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long analysisId;

	@Column(nullable = false, length = 100)
	private String topic;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private Generator generator;

	@Column(length = 64)
	private String model;

	@Column(nullable = false, length = 30)
	private String promptVersion;

	private Integer inputTokens;

	private Integer outputTokens;

	@Column(precision = 10, scale = 6)
	private BigDecimal costUsd;

	private long latencyMs;

	@Column(length = 300)
	private String fallbackReason;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String resultJson;

	@Column(nullable = false)
	private Instant createdAt;

	public static Recommendation of(Long analysisId, String topic, Generator generator, String model, String promptVersion,
			Integer inputTokens, Integer outputTokens, BigDecimal costUsd, long latencyMs, String fallbackReason,
			String resultJson, Instant createdAt) {
		Recommendation r = new Recommendation();
		r.analysisId = analysisId;
		r.topic = topic;
		r.generator = generator;
		r.model = model;
		r.promptVersion = promptVersion;
		r.inputTokens = inputTokens;
		r.outputTokens = outputTokens;
		r.costUsd = costUsd;
		r.latencyMs = latencyMs;
		r.fallbackReason = fallbackReason;
		r.resultJson = resultJson;
		r.createdAt = createdAt;
		return r;
	}
}
