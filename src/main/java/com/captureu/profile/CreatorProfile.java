package com.captureu.profile;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 크리에이터 컨셉. 인스타 계정마다 하나. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorProfile {

	@Id
	@Column(length = 32)
	private String igUserId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private Concept mainConcept;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(length = 20)
	private Concept subConcept;

	@Column(nullable = false)
	private Instant updatedAt;

	CreatorProfile(String igUserId) {
		this.igUserId = igUserId;
	}

	void change(Concept main, Concept sub, Instant now) {
		this.mainConcept = main;
		this.subConcept = sub;
		this.updatedAt = now;
	}
}
