package com.captureu.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 분석 시점의 계정 정보. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountSnapshot {

	@Id
	private Long analysisId;

	@Column(nullable = false, length = 32)
	private String igUserId;

	@Column(length = 64)
	private String username;

	private Integer followersCount;

	/** 계정 정보의 media_count. 실제 게시물 수와 다를 수 있어 참고용. */
	private Integer reportedMediaCount;

	public AccountSnapshot(Long analysisId, String igUserId, String username, Integer followersCount,
			Integer reportedMediaCount) {
		this.analysisId = analysisId;
		this.igUserId = igUserId;
		this.username = username;
		this.followersCount = followersCount;
		this.reportedMediaCount = reportedMediaCount;
	}
}
