package com.captureu.domain.analysis.util;

import java.time.Instant;
import java.util.Optional;

import org.springframework.web.util.UriComponentsBuilder;

/** 인스타 CDN URL 도우미. */
public final class CdnUrls {

	private CdnUrls() {
	}

	/** CDN URL의 oe 파라미터(16진수 유닉스 시각)로 만료 시각을 구한다. */
	public static Optional<Instant> expiresAt(String url) {
		if (url == null) {
			return Optional.empty();
		}
		try {
			String oe = UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("oe");
			return oe == null ? Optional.empty() : Optional.of(Instant.ofEpochSecond(Long.parseLong(oe, 16)));
		}
		catch (RuntimeException e) {
			return Optional.empty();
		}
	}
}
