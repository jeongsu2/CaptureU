package com.captureu.domain.recommendation.entity;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.captureu.domain.analysis.util.CaptionHook.HookType;
import com.captureu.domain.recommendation.dto.RecommendationDraft;

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

/** "이거 쓸게요"를 누른 첫 줄 후보. 효과 검증(#14)에서 새 게시물과 연결한다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecommendationAdoption {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long recommendationId;

	private int hookIndex;

	@Column(nullable = false, length = 500)
	private String hookText;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private HookType hookType;

	@Column(nullable = false, length = 30)
	private String promptVersion;

	@Column(nullable = false)
	private Instant adoptedAt;

	public enum MatchStatus {
		/** 사용자가 "이 게시물에 썼어요"로 연결. 자동 연결은 저장하지 않고 조회할 때마다 계산한다 */
		CONFIRMED
	}

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(length = 20)
	private MatchStatus matchStatus;

	@Column(length = 32)
	private String matchedMediaId;

	private Double matchScore;

	private Instant matchedAt;

	@jakarta.persistence.Version
	private long version;

	/** 사용자가 이 채택을 쓴 게시물을 확인했는지. */
	public boolean isConfirmed() {
		return matchStatus == MatchStatus.CONFIRMED;
	}

	public void confirm(String mediaId, Double score, Instant now) {
		this.matchStatus = MatchStatus.CONFIRMED;
		this.matchedMediaId = mediaId;
		this.matchScore = score;
		this.matchedAt = now;
	}

	public void clearConfirmation() {
		this.matchStatus = null;
		this.matchedMediaId = null;
		this.matchScore = null;
		this.matchedAt = null;
	}

	public static RecommendationAdoption of(Recommendation r, int hookIndex, RecommendationDraft.HookCandidate hook,
			Instant now) {
		RecommendationAdoption a = new RecommendationAdoption();
		a.recommendationId = r.getId();
		a.hookIndex = hookIndex;
		a.hookText = hook.text();
		a.hookType = hook.type();
		a.promptVersion = r.getPromptVersion();
		a.adoptedAt = now;
		return a;
	}
}
