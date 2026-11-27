package com.captureu.reference;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 사용자가 고른 참고 계정. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReferenceAccount {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 32)
	private String ownerIgUserId;

	@Column(nullable = false, length = 30)
	private String username;

	@Column(length = 32)
	private String igId;

	private Integer followersCount;

	@Column(nullable = false)
	private Instant registeredAt;

	public ReferenceAccount(String ownerIgUserId, String username, String igId, Integer followersCount,
			Instant registeredAt) {
		this.ownerIgUserId = ownerIgUserId;
		this.username = username;
		this.igId = igId;
		this.followersCount = followersCount;
		this.registeredAt = registeredAt;
	}
}
