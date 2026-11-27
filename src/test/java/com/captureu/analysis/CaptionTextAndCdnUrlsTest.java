package com.captureu.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class CaptionTextAndCdnUrlsTest {

	@Test
	void 첫_줄은_비어_있지_않은_첫_번째_줄이다() {
		assertThat(CaptionText.firstLine("\n\n  무신사 블프 존예들만  \n둘째 줄")).isEqualTo("무신사 블프 존예들만");
		assertThat(CaptionText.firstLine("한 줄\r\n둘째")).isEqualTo("한 줄");
		assertThat(CaptionText.firstLine("   ")).isNull();
		assertThat(CaptionText.firstLine(null)).isNull();
	}

	@Test
	void 첫_줄이_너무_길면_자른다() {
		assertThat(CaptionText.firstLine("가".repeat(600))).hasSize(CaptionText.MAX_LENGTH);
	}

	@Test
	void CDN_URL의_oe_파라미터로_만료_시각을_구한다() {
		assertThat(CdnUrls.expiresAt("https://scontent.cdninstagram.com/v/a.jpg?stp=x&oe=6750A000&_nc=1"))
			.contains(Instant.ofEpochSecond(0x6750A000L));
		assertThat(CdnUrls.expiresAt("https://x/no-param.jpg")).isEmpty();
		assertThat(CdnUrls.expiresAt("https://x/a.jpg?oe=zzz")).isEmpty();
		assertThat(CdnUrls.expiresAt(null)).isEmpty();
	}
}
