package com.captureu.domain.reference.dto.response;

import java.time.Instant;

import com.captureu.domain.reference.entity.ReferenceAccount;

public record ReferenceAccountResponse(String username, String igId, Integer followersCount, Instant registeredAt) {

	public static ReferenceAccountResponse of(ReferenceAccount account) {
		return new ReferenceAccountResponse(account.getUsername(), account.getIgId(), account.getFollowersCount(),
				account.getRegisteredAt());
	}
}
