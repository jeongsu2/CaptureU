package com.captureu.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextsTest {

	@Test
	void 이모지를_반으로_쪼개지_않고_글자_수로_자른다() {
		String text = "😀😀😀abc";

		assertThat(Texts.truncate(text, 2)).isEqualTo("😀😀");
		assertThat(Texts.truncate(text, 4)).isEqualTo("😀😀😀a");
	}

	@Test
	void 짧거나_null이면_그대로_돌려준다() {
		assertThat(Texts.truncate("짧음", 10)).isEqualTo("짧음");
		assertThat(Texts.truncate(null, 10)).isNull();
	}
}
