package com.captureu.analysis;

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

/** 분석 시점의 계정 정보. 분석 ID를 그대로 ID로 쓴다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountSnapshot implements Persistable<Long> {

	@Id
	private Long analysisId;

	@Column(nullable = false, length = 32)
	private String igUserId;

	@Column(length = 64)
	private String username;

	private Integer followersCount;

	/** 계정 정보의 media_count. 실제 게시물 수와 다를 수 있어 참고용. */
	private Integer reportedMediaCount;

	@Transient
	private boolean isNew;

	public AccountSnapshot(Long analysisId, String igUserId, String username, Integer followersCount,
			Integer reportedMediaCount) {
		this.analysisId = analysisId;
		this.igUserId = igUserId;
		this.username = username;
		this.followersCount = followersCount;
		this.reportedMediaCount = reportedMediaCount;
		this.isNew = true;
	}

	@Override
	public Long getId() {
		return analysisId;
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
