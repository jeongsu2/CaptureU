package com.captureu.recommendation;

import com.captureu.llm.LlmProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RecommendationConfig {

	@Bean
	ClaudeHookWriter claudeHookWriter(LlmProperties properties) {
		return new ClaudeHookWriter(properties);
	}
}
