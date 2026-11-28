package com.captureu.domain.instagram.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Instagram Graph API 설정.
 *
 * @param baseUrl        Unified Login(시스템 사용자 토큰)은 graph.facebook.com을 쓴다
 * @param apiVersion     버전을 고정해야 Meta의 기본 버전이 바뀌어도 응답 형식이 갑자기 바뀌지 않는다
 * @param accessToken    지금은 로컬 .env의 IG_ACCESS_TOKEN. 사용자별 암호화 저장은 로그인 흐름(#16)에서 한다
 * @param igUserId       토큰에 연결된 내 인스타 계정 ID
 * @param maxRetries     재시도해도 되는 에러(호출 한도, 일시 장애)에서 최대 재시도 횟수
 * @param retryDelay     첫 재시도 대기 시간. 이후 2배씩 늘린다
 */
@ConfigurationProperties("captureu.instagram.graph")
public record GraphApiProperties(
		@DefaultValue("https://graph.facebook.com") String baseUrl,
		@DefaultValue("v27.0") String apiVersion,
		String accessToken,
		String igUserId,
		@DefaultValue("5s") Duration connectTimeout,
		@DefaultValue("20s") Duration readTimeout,
		@DefaultValue("3") int maxRetries,
		@DefaultValue("1s") Duration retryDelay) {

	public boolean hasAccessToken() {
		return accessToken != null && !accessToken.isBlank();
	}

	/** record 기본 toString은 모든 값을 찍으므로 토큰을 가린다. */
	@Override
	public String toString() {
		return "GraphApiProperties[baseUrl=%s, apiVersion=%s, accessToken=%s, igUserId=%s, connectTimeout=%s, readTimeout=%s, maxRetries=%d, retryDelay=%s]"
			.formatted(baseUrl, apiVersion, hasAccessToken() ? "***" : "(없음)", igUserId, connectTimeout,
					readTimeout, maxRetries, retryDelay);
	}
}
