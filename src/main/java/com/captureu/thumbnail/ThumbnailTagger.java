package com.captureu.thumbnail;

import java.math.BigDecimal;

/** 썸네일 이미지에서 특징을 뽑는다. 키가 없으면 enabled()가 false다. */
interface ThumbnailTagger {

	boolean enabled();

	/** 결과를 다시 쓸 수 있는지 가르는 버전. 저장된 특징의 버전이 다르면 다시 분석한다. */
	String promptVersion();

	Tagged tag(ThumbnailImageFetcher.Image image);

	record Tagged(ThumbnailTags tags, String model, String promptVersion, int inputTokens, int outputTokens,
			BigDecimal costUsd) {
	}
}
