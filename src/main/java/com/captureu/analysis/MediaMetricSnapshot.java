package com.captureu.analysis;

import java.time.Duration;
import java.time.Instant;

import com.captureu.instagram.account.MediaInsights;
import com.captureu.instagram.account.MediaItem;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 분석 요청별 게시물 지표. 같은 게시물을 다시 분석하면 행이 하나 더 쌓인다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MediaMetricSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long analysisId;

	@Column(nullable = false, length = 32)
	private String mediaId;

	/** 이 게시물을 어느 계정의 게시물로 수집했는지 (협업 게시물은 두 계정에 모두 속한다). */
	@Column(nullable = false, length = 32)
	private String accountIgId;

	@Column(nullable = false)
	private Instant collectedAt;

	/** 수집 시점의 게시 후 경과 시간. 같은 나이끼리 비교할 때 쓴다. */
	private int ageHours;

	private Integer likeCount;
	private Integer commentsCount;
	private Integer views;
	private Integer reach;
	private Integer saved;
	private Integer shares;
	private Integer totalInteractions;
	private Integer avgWatchTimeMs;
	private Double skipRate;

	@Column(length = 200)
	private String insightError;

	/** 참고 계정 게시물. 공개 지표(조회수·좋아요·댓글 수)만 있다. */
	public static MediaMetricSnapshot ofReference(Long analysisId, String accountIgId, MediaItem item,
			Integer viewCount, Instant collectedAt) {
		MediaMetricSnapshot s = base(analysisId, accountIgId, item, collectedAt);
		s.views = viewCount;
		return s;
	}

	private static MediaMetricSnapshot base(Long analysisId, String accountIgId, MediaItem item, Instant collectedAt) {
		MediaMetricSnapshot s = new MediaMetricSnapshot();
		s.analysisId = analysisId;
		s.accountIgId = accountIgId;
		s.mediaId = item.id();
		s.collectedAt = collectedAt;
		s.ageHours = item.postedAt() == null ? 0
				: (int) Math.max(0, Duration.between(item.postedAt(), collectedAt).toHours());
		s.likeCount = item.likeCount();
		s.commentsCount = item.commentsCount();
		return s;
	}

	/** 내 계정 게시물. 인사이트(저장·공유·스킵률 등)까지 있다. */
	public static MediaMetricSnapshot of(Long analysisId, String accountIgId, MediaItem item, MediaInsights insights,
			Instant collectedAt) {
		MediaMetricSnapshot s = base(analysisId, accountIgId, item, collectedAt);
		s.views = insights.views();
		s.reach = insights.reach();
		s.saved = insights.saved();
		s.shares = insights.shares();
		s.totalInteractions = insights.totalInteractions();
		s.avgWatchTimeMs = insights.avgWatchTimeMs();
		s.skipRate = insights.skipRate();
		s.insightError = insights.error();
		return s;
	}
}
