package com.captureu.domain.tracking.entity;

import java.time.Duration;
import java.time.Instant;

import com.captureu.domain.analysis.util.CaptionText;
import com.captureu.domain.instagram.dto.MediaInsights;
import com.captureu.domain.instagram.dto.MediaItem;
import com.captureu.global.util.Texts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 게시 후 N시간 시점의 누적 지표 (#5). 게시물·나이마다 한 번만 저장한다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MediaCheckpoint {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 32)
	private String igUserId;

	@Column(nullable = false, length = 32)
	private String mediaId;

	private int checkpointHours;

	@Column(nullable = false)
	private Instant postedAt;

	@Column(nullable = false)
	private Instant capturedAt;

	/** 실제로 잰 시점의 게시물 나이. 스케줄러가 늦으면 checkpointHours보다 조금 크다. */
	private int ageMinutes;

	private Integer views;

	private Integer reach;

	private Integer saved;

	private Integer shares;

	private Integer totalInteractions;

	private Double skipRate;

	@Column(length = 100)
	private String captionFirstLine;

	@Column(length = 255)
	private String permalink;

	public static MediaCheckpoint of(String igUserId, MediaItem media, int checkpointHours, MediaInsights insights,
			Instant now) {
		MediaCheckpoint c = new MediaCheckpoint();
		c.igUserId = igUserId;
		c.mediaId = media.id();
		c.checkpointHours = checkpointHours;
		c.postedAt = media.postedAt();
		c.capturedAt = now;
		c.ageMinutes = (int) Duration.between(media.postedAt(), now).toMinutes();
		c.views = insights.views();
		c.reach = insights.reach();
		c.saved = insights.saved();
		c.shares = insights.shares();
		c.totalInteractions = insights.totalInteractions();
		c.skipRate = insights.skipRate();
		String first = CaptionText.firstLine(media.caption());
		c.captionFirstLine = first == null || first.isBlank() ? null : Texts.truncate(first, 100);
		c.permalink = media.permalink() == null || media.permalink().length() > 255 ? null : media.permalink();
		return c;
	}
}
