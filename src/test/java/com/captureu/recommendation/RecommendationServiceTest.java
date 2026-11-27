package com.captureu.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecommendationServiceTest {

	@Test
	void LLM_실패_이유에는_외부_오류_원문을_남기지_않는다() {
		String reason = RecommendationService
			.failureCode(new IllegalStateException("connect to internal-host:8443 failed, request_id=req_123"));

		assertThat(reason).isEqualTo("LLM_ERROR: IllegalStateException");
	}
}
