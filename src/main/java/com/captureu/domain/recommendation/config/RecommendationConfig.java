package com.captureu.domain.recommendation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.captureu.domain.llm.config.LlmProperties;
import com.captureu.domain.recommendation.client.ClaudeHookWriter;

@Configuration
public class RecommendationConfig {

	@Bean
	ClaudeHookWriter claudeHookWriter(LlmProperties properties) {
		return new ClaudeHookWriter(properties);
	}
}
