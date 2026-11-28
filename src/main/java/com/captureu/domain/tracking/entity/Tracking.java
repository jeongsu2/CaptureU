package com.captureu.domain.tracking.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 계정별 '더 이어서 하기' 신청 상태 (#5). 켠 계정만 스케줄러가 게시물을 추적한다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tracking {

	@Id
	@Column(length = 32)
	private String igUserId;

	private boolean enabled;

	/** 마지막으로 켠 시각. */
	@Column(nullable = false)
	private Instant startedAt;

	@Column(nullable = false)
	private Instant updatedAt;

	public static Tracking start(String igUserId, Instant now) {
		Tracking t = new Tracking();
		t.igUserId = igUserId;
		t.enabled = true;
		t.startedAt = now;
		t.updatedAt = now;
		return t;
	}

	public void change(boolean enabled, Instant now) {
		if (enabled && !this.enabled) {
			this.startedAt = now;
		}
		this.enabled = enabled;
		this.updatedAt = now;
	}
}
