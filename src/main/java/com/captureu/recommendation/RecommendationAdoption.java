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
