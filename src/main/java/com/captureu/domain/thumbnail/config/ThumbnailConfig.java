package com.captureu.domain.thumbnail.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.captureu.domain.llm.config.LlmProperties;
import com.captureu.domain.thumbnail.client.ClaudeThumbnailTagger;
import com.captureu.domain.thumbnail.client.ThumbnailImageFetcher;
import com.captureu.domain.thumbnail.client.ThumbnailTagger;

@Configuration
public class ThumbnailConfig {

	@Bean
	ThumbnailTagger thumbnailTagger(LlmProperties properties) {
		return new ClaudeThumbnailTagger(properties);
	}

	@Bean
	ThumbnailImageFetcher thumbnailImageFetcher() {
		return new ThumbnailImageFetcher();
	}
}
