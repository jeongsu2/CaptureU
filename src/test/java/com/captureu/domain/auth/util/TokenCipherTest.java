package com.captureu.domain.auth.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class TokenCipherTest {

	private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

	@Test
	void 암호화한_토큰을_되돌리고_같은_토큰도_매번_다르게_암호화한다() {
		TokenCipher cipher = new TokenCipher(KEY);

		String a = cipher.encrypt("EAAsecret-token");
		String b = cipher.encrypt("EAAsecret-token");

		assertThat(a).isNotEqualTo(b).doesNotContain("EAAsecret");
		assertThat(cipher.decrypt(a)).isEqualTo("EAAsecret-token");
	}

	@Test
	void 암호문이_바뀌거나_키가_다르면_복호화하지_않는다() {
		String stored = new TokenCipher(KEY).encrypt("EAAsecret-token");
		byte[] tampered = Base64.getDecoder().decode(stored);
		tampered[tampered.length - 1] ^= 1;
		byte[] other = new byte[32];
		other[0] = 1;

		assertThatThrownBy(() -> new TokenCipher(KEY).decrypt(Base64.getEncoder().encodeToString(tampered)))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new TokenCipher(Base64.getEncoder().encodeToString(other)).decrypt(stored))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 키는_32바이트여야_한다() {
		assertThatThrownBy(() -> new TokenCipher(Base64.getEncoder().encodeToString(new byte[16])))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
