package com.captureu.llm;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 추천 문구 생성에 쓰는 Claude API 설정 (#13). 키가 없으면 LLM을 쓰지 않고 템플릿으로 추천한다.
 *
 * @param apiKey          로컬 .env의 ANTHROPIC_API_KEY
 * @param refusalFallback 안전 분류기가 요청을 거절하면 서버에서 다른 모델로 다시 시도한다
 * @param mock            로컬 목 서버(mock-llm 프로필)로 부르는 중. 결과의 프롬프트 버전에 -mock을 붙여 실제 결과와 섞이지 않게 한다
 */
@ConfigurationProperties("captureu.llm.anthropic")
public record LlmProperties(
		String apiKey,
		@DefaultValue("claude-opus-5-5") String model,
		@DefaultValue("https://api.anthropic.com") String baseUrl,
		@DefaultValue("60s") Duration timeout,
		@DefaultValue("true") boolean refusalFallback,
		@DefaultValue("false") boolean mock) {

	public boolean hasApiKey() {
		return apiKey != null && !apiKey.isBlank();
	}

	/** 목 서버 결과는 버전이 달라 실제 분석 캐시로 쓰이지 않는다. */
	public String promptVersion(String base) {
		return mock ? base + "-mock" : base;
	}

	/** record 기본 toString은 모든 값을 찍으므로 키를 가린다. */
	@Override
	public String toString() {
		return "LlmProperties[apiKey=%s, model=%s, baseUrl=%s, timeout=%s, refusalFallback=%s, mock=%s]"
			.formatted(hasApiKey() ? "***" : "(없음)", model, baseUrl, timeout, refusalFallback, mock);
	}
}
