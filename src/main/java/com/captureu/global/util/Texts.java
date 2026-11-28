package com.captureu.global.util;

/** 문자열 도우미. */
public final class Texts {

	private Texts() {
	}

	/**
	 * 글자(코드 포인트) 기준으로 자른다. String.substring은 UTF-16 단위로 잘라서
	 * 이모지 같은 4바이트 문자를 반으로 쪼갤 수 있다.
	 */
	public static String truncate(String text, int maxCodePoints) {
		if (text == null || text.codePointCount(0, text.length()) <= maxCodePoints) {
			return text;
		}
		return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
	}
}
