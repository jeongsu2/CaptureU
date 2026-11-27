package com.captureu.analysis;

import java.time.Instant;

import org.springframework.data.domain.Persistable;

import com.captureu.instagram.account.MediaItem;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 게시물. 분석할 때마다 최신 값으로 덮어쓴다.
 * ID를 직접 정하는 엔티티라 {@link Persistable}로 새 행인지 알려 준다.
 * (없으면 save가 merge로 동작해 새 게시물마다 SELECT를 한 번 더 한다)
 */
@Entity
@Table(name = "ig_media")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IgMedia implements Persistable<String> {

	@Id
	@Column(length = 32)
	private String id;

	@Column(nullable = false, length = 32)
	private String ownerIgUserId;

	@Column(nullable = false, length = 20)
	private String mediaType;

	@Column(nullable = false, length = 20)
	private String mediaProductType;

	@Column(nullable = false)
	private Instant postedAt;

	@Column(columnDefinition = "TEXT")
	private String caption;

	@Column(length = 500)
	private String captionFirstLine;

	@Column(length = 2048)
	private String thumbnailUrl;

	/** 썸네일 URL은 며칠 뒤 만료된다. 만료 후 이미지가 필요하면 API로 다시 받는다. */
	private Instant thumbnailUrlExpiresAt;

	@Column(length = 512)
	private String permalink;

	@Column(nullable = false)
	private Instant firstSeenAt;

	@Column(nullable = false)
	private Instant lastSeenAt;

	@Transient
	private boolean isNew;

	public static IgMedia from(String ownerIgUserId, MediaItem item, Instant now) {
		IgMedia media = new IgMedia();
		media.id = item.id();
		media.ownerIgUserId = ownerIgUserId;
		media.firstSeenAt = now;
		media.isNew = true;
		media.update(item, now);
		return media;
	}

	public void update(MediaItem item, Instant now) {
		this.mediaType = item.mediaType();
		this.mediaProductType = item.productType();
		this.postedAt = item.postedAt();
		this.caption = item.caption();
		this.captionFirstLine = CaptionText.firstLine(item.caption());
		this.thumbnailUrl = item.coverImageUrl();
		this.thumbnailUrlExpiresAt = CdnUrls.expiresAt(this.thumbnailUrl).orElse(null);
		this.permalink = item.permalink();
		this.lastSeenAt = now;
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
