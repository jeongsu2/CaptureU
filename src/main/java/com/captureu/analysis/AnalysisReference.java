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

	@Column(length = 30)
	private String failureType;

	@Column(length = 500)
	private String failureMessage;

	static AnalysisReference done(Long analysisId, String username, String igId, Integer followersCount,
			int mediaCount) {
		AnalysisReference r = new AnalysisReference();
		r.analysisId = analysisId;
		r.username = username;
		r.igId = igId;
		r.status = AnalysisStatus.DONE;
		r.followersCount = followersCount;
		r.mediaCount = mediaCount;
		return r;
	}

	static AnalysisReference failed(Long analysisId, String username, String failureType, String failureMessage) {
		AnalysisReference r = new AnalysisReference();
		r.analysisId = analysisId;
		r.username = username;
		r.status = AnalysisStatus.FAILED;
		r.failureType = failureType;
		r.failureMessage = Texts.truncate(failureMessage, 500);
		return r;
	}
}
