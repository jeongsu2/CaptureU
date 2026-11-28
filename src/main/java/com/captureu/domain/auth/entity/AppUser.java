package com.captureu.domain.auth.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 인스타로 로그인한 사용자 (#16). 토큰은 암호화한 값만 가진다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser {

	@Id
	@Column(length = 32)
	private String igUserId;

	@Column(length = 64)
	private String username;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String accessTokenEnc;

	/** null이면 만료 없음 (시스템 사용자 토큰). */
	private Instant tokenExpiresAt;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	public static AppUser create(String igUserId, String username, String accessTokenEnc, Instant expiresAt,
			Instant now) {
		AppUser u = new AppUser();
		u.igUserId = igUserId;
		u.createdAt = now;
		u.refresh(username, accessTokenEnc, expiresAt, now);
		return u;
	}

	/** 다시 로그인하면 새 토큰으로 바꾼다. */
	public void refresh(String username, String accessTokenEnc, Instant expiresAt, Instant now) {
		this.username = username;
		this.accessTokenEnc = accessTokenEnc;
		this.tokenExpiresAt = expiresAt;
		this.updatedAt = now;
	}

	@Override
	public String toString() {
		return "AppUser[igUserId=%s, username=%s]".formatted(igUserId, username);
	}
}
