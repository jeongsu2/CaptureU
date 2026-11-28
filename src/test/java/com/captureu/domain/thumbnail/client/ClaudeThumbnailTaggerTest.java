package com.captureu.domain.thumbnail.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.captureu.domain.llm.controller.MockAnthropicController;
import com.captureu.domain.llm.controller.MockAnthropicServer;
import com.captureu.domain.thumbnail.client.ThumbnailImageFetcher.Image;
import com.captureu.domain.thumbnail.client.ThumbnailTagger.Tagged;

class ClaudeThumbnailTaggerTest {

	@Test
	void 목_서버로_이미지를_보내_특징을_받고_목_버전으로_기록한다() throws Exception {
		try (var mock = new MockAnthropicServer(); var tagger = new ClaudeThumbnailTagger(mock.properties())) {
			Image image = new Image("thumbnail".getBytes(StandardCharsets.UTF_8), "image/jpeg");

			Tagged first = tagger.tag(image);
			Tagged again = tagger.tag(image);

			assertThat(first.tags()).isNotNull().isEqualTo(again.tags());
			assertThat(first.tags().tone()).isNotNull();
			assertThat(first.model()).isEqualTo(MockAnthropicController.MODEL);
			assertThat(first.promptVersion()).isEqualTo("thumb-v1-mock");
			assertThat(first.costUsd()).isNull();
		}
	}
}
