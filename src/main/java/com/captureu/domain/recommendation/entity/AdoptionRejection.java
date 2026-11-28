package com.captureu.domain.recommendation.entity;

import java.io.Serializable;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** "이 게시물 아니에요"를 누른 (채택, 게시물). 이 게시물에는 이 채택을 다시 자동 연결하지 않는다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdoptionRejection {

	@EmbeddedId
	private Key id;

	@Column(nullable = false)
	private Instant rejectedAt;

	AdoptionRejection(Long adoptionId, String mediaId, Instant now) {
		this.id = new Key(adoptionId, mediaId);
		this.rejectedAt = now;
	}

	@Embeddable
	public record Key(@Column(name = "adoption_id") Long adoptionId,
			@Column(name = "media_id", length = 32) String mediaId) implements Serializable {
	}
}
