package com.captureu.domain.instagram.dto;

import java.time.Instant;

/**
 * 호출 한도 사용량. Graph API 응답 헤더(x-business-use-case-usage, x-app-usage)에서 읽는다.
 *
 * @param callCountPercent      호출 횟수 사용률(%)
 * @param totalCpuTimePercent   CPU 시간 사용률(%)
 * @param totalTimePercent      처리 시간 사용률(%)
 * @param regainAccessMinutes   한도에 걸렸을 때 다시 호출할 수 있을 때까지 남은 시간(분). 0이면 걸리지 않음
 */
public record GraphApiUsage(int callCountPercent, int totalCpuTimePercent, int totalTimePercent,
		int regainAccessMinutes, Instant observedAt) {

	public int maxPercent() {
		return Math.max(callCountPercent, Math.max(totalCpuTimePercent, totalTimePercent));
	}
}
