package com.captureu.recommendation;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.captureu.analysis.CaptionHook.HookType;

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
		/** 캡션이 충분히 비슷해 자동으로 연결 */
		AUTO,
		/** 사용자가 "이 게시물에 썼어요"로 연결 */
		CONFIRMED,
		/** 사용자가 "아니에요"로 뗌. matchedMediaId는 뗀 게시물이고, 그 게시물에는 다시 자동 연결하지 않는다 */
		REJECTED
	}

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(length = 20)
	private MatchStatus matchStatus;

	@Column(length = 32)
	private String matchedMediaId;

	private Double matchScore;

	private Instant matchedAt;

	/** 이 채택을 쓴 게시물이 정해졌는지 (자동 또는 사용자 확인). */
	public boolean isMatched() {
		return matchStatus == MatchStatus.AUTO || matchStatus == MatchStatus.CONFIRMED;
	}

	void match(MatchStatus status, String mediaId, Double score, Instant now) {
		this.matchStatus = status;
		this.matchedMediaId = mediaId;
		this.matchScore = score;
		this.matchedAt = now;
	}

	static RecommendationAdoption of(Recommendation r, int hookIndex, RecommendationDraft.HookCandidate hook,
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
