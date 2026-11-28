package com.captureu.domain.auth.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 사용자 토큰을 AES-256-GCM으로 암호화한다 (#16). 저장 형식은 Base64(IV 12바이트 + 암호문·인증 태그).
 * 매번 새 IV를 쓰므로 같은 토큰도 암호문이 다르고, 암호문이 바뀌면 복호화가 실패한다.
 */
public final class TokenCipher {

	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final SecretKey key;

	public TokenCipher(String base64Key) {
		byte[] raw = Base64.getDecoder().decode(base64Key);
		if (raw.length != 32) {
			throw new IllegalArgumentException("토큰 암호화 키는 Base64로 인코딩한 32바이트여야 합니다");
		}
		this.key = new SecretKeySpec(raw, "AES");
	}

	public String encrypt(String plain) {
		try {
			byte[] iv = new byte[IV_BYTES];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder()
				.encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("토큰 암호화 실패", e);
		}
	}

	public String decrypt(String stored) {
		try {
			byte[] all = Base64.getDecoder().decode(stored);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
			return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IllegalStateException("토큰 복호화 실패 (키가 바뀌었거나 값이 손상됨)", e);
		}
	}
}
