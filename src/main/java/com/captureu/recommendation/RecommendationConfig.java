package com.captureu.recommendation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
class RecommendationConfig {

	@Bean
	ClaudeHookWriter claudeHookWriter(LlmProperties properties) {
		return new ClaudeHookWriter(properties);
	}
}
