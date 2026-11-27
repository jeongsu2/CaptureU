package com.captureu.analysis;

import com.captureu.instagram.account.DemographicRow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 분석 시점의 팔로워·참여자 분포. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AudienceDemographic {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long analysisId;

	@Column(nullable = false, length = 40)
	private String metric;

	@Column(nullable = false, length = 20)
	private String breakdown;

	@Column(nullable = false, length = 40)
	private String dimensionValue;

	private int value;

	public static AudienceDemographic of(Long analysisId, DemographicRow row) {
		AudienceDemographic d = new AudienceDemographic();
		d.analysisId = analysisId;
		d.metric = row.metric();
		d.breakdown = row.breakdown();
		d.dimensionValue = row.dimensionValue();
		d.value = row.value();
		return d;
	}
}
