package com.captureu.domain.instagram.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenMaskerTest {

	@Test
	void paging_next_URL의_토큰을_가린다() {
		String next = "https://graph.facebook.com/v27.0/1/media?access_token=EAAabc123&fields=id&after=QVFI";

		assertThat(TokenMasker.mask(next))
			.isEqualTo("https://graph.facebook.com/v27.0/1/media?access_token=***&fields=id&after=QVFI");
	}

	@Test
	void 따옴표_안의_URL과_여러_개의_토큰도_가린다() {
		String text = "I/O error on GET \"https://x?access_token=EAA1\" and access_token=IGAA2 end";

		assertThat(TokenMasker.mask(text)).doesNotContain("EAA1").doesNotContain("IGAA2");
	}

	@Test
	void 쿼리_형태가_아닌_곳의_토큰도_가린다() {
		assertThat(TokenMasker.mask("token is EAAsecret", "EAAsecret")).isEqualTo("token is ***");
	}

	@Test
	void null은_그대로_돌려준다() {
		assertThat(TokenMasker.mask(null)).isNull();
	}
}
