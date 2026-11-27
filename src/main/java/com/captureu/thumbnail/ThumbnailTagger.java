package com.captureu.thumbnail;

import java.math.BigDecimal;

/** 썸네일 이미지에서 특징을 뽑는다. 키가 없으면 enabled()가 false다. */
interface ThumbnailTagger {

	boolean enabled();

	Tagged tag(ThumbnailImageFetcher.Image image);

	record Tagged(ThumbnailTags tags, String model, String promptVersion, int inputTokens, int outputTokens,
			BigDecimal costUsd) {
	}
}
