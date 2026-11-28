package com.captureu.thumbnail;

import java.time.Instant;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 게시물 → 썸네일 이미지 해시. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MediaThumbnail implements Persistable<String> {

	@Id
	@Column(length = 32)
	private String mediaId;

	@Column(nullable = false, length = 64)
	private String imageHash;

	@Column(nullable = false)
	private Instant taggedAt;

	@Transient
	private boolean isNew;

	MediaThumbnail(String mediaId, String imageHash, Instant now) {
		this.mediaId = mediaId;
		this.imageHash = imageHash;
		this.taggedAt = now;
		this.isNew = true;
	}

	/** 같은 게시물의 썸네일 이미지가 바뀌었을 때. */
	void relink(String imageHash, Instant now) {
		this.imageHash = imageHash;
		this.taggedAt = now;
	}

	@Override
	public String getId() {
		return mediaId;
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
