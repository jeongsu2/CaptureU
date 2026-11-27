package com.captureu.thumbnail;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.captureu.llm.LlmProperties;

@Configuration
class ThumbnailConfig {

	@Bean
	ThumbnailTagger thumbnailTagger(LlmProperties properties) {
		return new ClaudeThumbnailTagger(properties);
	}

	@Bean
	ThumbnailImageFetcher thumbnailImageFetcher() {
		return new ThumbnailImageFetcher();
	}
}
