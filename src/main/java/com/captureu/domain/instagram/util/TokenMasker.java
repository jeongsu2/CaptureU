package com.captureu.domain.instagram.util;

import java.util.regex.Pattern;

/**
 * 로그와 예외 메시지에서 access_token 값을 가린다.
 * Graph API는 응답의 paging.next URL에 토큰을 그대로 넣어 돌려주고,
 * HTTP 클라이언트의 I/O 예외 메시지에도 요청 URL(토큰 포함)이 들어간다.
 */
public final class TokenMasker {

	private static final Pattern TOKEN_PARAM = Pattern.compile("(access_token=)[^&\\s\"']+");

	private TokenMasker() {
	}

	public static String mask(String text) {
		if (text == null) {
			return null;
		}
		return TOKEN_PARAM.matcher(text).replaceAll("$1***");
	}

	/** 쿼리 파라미터 형태가 아닌 곳에 토큰이 그대로 들어간 경우까지 가린다. */
	public static String mask(String text, String token) {
		String masked = mask(text);
		if (masked == null || token == null || token.isBlank()) {
			return masked;
		}
		return masked.replace(token, "***");
	}
}
