package com.captureu.thumbnail;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import com.captureu.thumbnail.ThumbnailTags.Composition;
import com.captureu.thumbnail.ThumbnailTags.TextSize;
import com.captureu.thumbnail.ThumbnailTags.Tone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 이미지 해시별 썸네일 특징과 분석 비용 (#11). */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ThumbnailFeature implements Persistable<String> {

	@Id
	@Column(length = 64)
	private String imageHash;

	private boolean faceCloseUp;

	private int personCount;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 10)
	private TextSize textSize;

	@Column(length = 100)
	private String textContent;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private Tone tone;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private Composition composition;

	@Column(nullable = false, length = 64)
	private String model;

	@Column(nullable = false, length = 30)
	private String promptVersion;

	private int inputTokens;

	private int outputTokens;

	@Column(precision = 10, scale = 6)
	private BigDecimal costUsd;

	@Column(nullable = false)
	private Instant createdAt;

	@Transient
	private boolean isNew;

	static ThumbnailFeature of(String hash, ThumbnailTags tags, String model, String promptVersion, int in, int out,
			BigDecimal cost, Instant now) {
		ThumbnailFeature f = new ThumbnailFeature();
		f.imageHash = hash;
		f.isNew = true;
		f.fill(tags, model, promptVersion, in, out, cost, now);
		return f;
	}

	/** 버전이 바뀌어 다시 분석한 결과로 덮어쓴다. */
	void retag(ThumbnailTags tags, String model, String promptVersion, int in, int out, BigDecimal cost, Instant now) {
		fill(tags, model, promptVersion, in, out, cost, now);
	}

	private void fill(ThumbnailTags tags, String model, String promptVersion, int in, int out, BigDecimal cost,
			Instant now) {
		ThumbnailFeature f = this;
		f.faceCloseUp = tags.faceCloseUp();
		f.personCount = Math.max(0, tags.personCount());
		f.textSize = tags.textSize() == null ? TextSize.NONE : tags.textSize();
		String text = tags.textContent() == null ? null : tags.textContent().strip();
		f.textContent = text == null || text.isEmpty() ? null : com.captureu.common.Texts.truncate(text, 100);
		f.tone = tags.tone() == null ? Tone.NEUTRAL : tags.tone();
		f.composition = tags.composition() == null ? Composition.OTHER : tags.composition();
		f.model = model;
		f.promptVersion = promptVersion;
		f.inputTokens = in;
		f.outputTokens = out;
		f.costUsd = cost;
		f.createdAt = now;
	}

	@Override
	public String getId() {
		return imageHash;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		this.isNew = false;
	}
}
