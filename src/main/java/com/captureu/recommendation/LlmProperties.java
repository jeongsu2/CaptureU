package com.captureu.recommendation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 추천 문구 생성에 쓰는 Claude API 설정 (#13). 키가 없으면 LLM을 쓰지 않고 템플릿으로 추천한다.
 *
 * @param apiKey          로컬 .env의 ANTHROPIC_API_KEY
 * @param refusalFallback 안전 분류기가 요청을 거절하면 서버에서 다른 모델로 다시 시도한다
 */
@ConfigurationProperties("captureu.llm.anthropic")
public record LlmProperties(
		String apiKey,
		@DefaultValue("claude-opus-5-5") String model,
		@DefaultValue("https://api.anthropic.com") String baseUrl,
		@DefaultValue("60s") Duration timeout,
		@DefaultValue("true") boolean refusalFallback) {

	public boolean hasApiKey() {
		return apiKey != null && !apiKey.isBlank();
	}

	/** record 기본 toString은 모든 값을 찍으므로 키를 가린다. */
	@Override
	public String toString() {
		return "LlmProperties[apiKey=%s, model=%s, baseUrl=%s, timeout=%s, refusalFallback=%s]"
			.formatted(hasApiKey() ? "***" : "(없음)", model, baseUrl, timeout, refusalFallback);
	}
}
