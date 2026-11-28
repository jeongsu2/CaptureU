package com.captureu.domain.instagram.dto;

/**
 * @param metric         follower_demographics 또는 engaged_audience_demographics
 * @param breakdown      age 또는 gender
 * @param dimensionValue 예: 18-24, F, M, U(알 수 없음)
 */
public record DemographicRow(String metric, String breakdown, String dimensionValue, int value) {
}
