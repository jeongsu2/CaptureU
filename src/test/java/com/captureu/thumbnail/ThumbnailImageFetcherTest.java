package com.captureu.thumbnail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.captureu.thumbnail.ThumbnailImageFetcher.ThumbnailFetchException;

class ThumbnailImageFetcherTest {

	@ParameterizedTest
	@ValueSource(strings = { "https://scontent-ssn1-1.cdninstagram.com/v/t51/x.jpg?oe=6750A000",
			"https://scontent.xx.fbcdn.net/v/x.jpg" })
	void 인스타그램_CDN의_https_주소만_받는다(String url) {
		assertThat(ThumbnailImageFetcher.allowed(url)).isNotNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://scontent.cdninstagram.com/x.jpg", "https://evil.example.com/x.jpg",
			"https://cdninstagram.com.evil.com/x.jpg", "https://scontent.cdninstagram.com:8443/x.jpg",
			"https://169.254.169.254/latest/meta-data", "file:///etc/passwd", "not a url" })
	void 그_밖의_주소는_부르지_않는다(String url) {
		assertThatThrownBy(() -> ThumbnailImageFetcher.allowed(url)).isInstanceOf(ThumbnailFetchException.class);
	}
}
