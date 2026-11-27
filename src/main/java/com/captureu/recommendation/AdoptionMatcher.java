package com.captureu.recommendation;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 채택한 첫 줄과 새 게시물 캡션 첫 줄이 얼마나 비슷한지 (#14).
 * 사람들은 추천 문구를 조금 고쳐 쓰므로(띄어쓰기, 이모지, 끝말) 정확히 같은지 대신
 * 공백·기호를 뺀 글자 2개 묶음(bigram)이 얼마나 겹치는지(Dice 계수, 0~1)로 본다.
 */
final class AdoptionMatcher {

	/** 이 이상이면 자동으로 연결한다. */
	static final double AUTO = 0.6;
	/** 이 이상이면 후보로 보여 주고 사용자에게 묻는다. */
	static final double ASK = 0.35;

	private AdoptionMatcher() {
	}

	static double similarity(String adopted, String caption) {
		String a = normalize(adopted);
		String b = normalize(caption);
		if (a.isEmpty() || b.isEmpty()) {
			return 0;
		}
		// 추천 문구를 그대로 넣고 앞뒤에 덧붙인 경우
		if (b.contains(a) || a.contains(b) && b.codePointCount(0, b.length()) >= 4) {
			return 1;
		}
		Map<String, Integer> left = bigrams(a);
		Map<String, Integer> right = bigrams(b);
		if (left.isEmpty() || right.isEmpty()) {
			return 0;
		}
		int overlap = 0;
		for (Map.Entry<String, Integer> e : left.entrySet()) {
			overlap += Math.min(e.getValue(), right.getOrDefault(e.getKey(), 0));
		}
		int total = left.values().stream().mapToInt(Integer::intValue).sum()
				+ right.values().stream().mapToInt(Integer::intValue).sum();
		return Math.round(2.0 * overlap / total * 1000) / 1000.0;
	}

	/** 글자와 숫자만 남긴다 (한글, 영문 소문자, 숫자). */
	static String normalize(String text) {
		if (text == null) {
			return "";
		}
		String nfc = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
		StringBuilder sb = new StringBuilder();
		nfc.codePoints().filter(Character::isLetterOrDigit).forEach(sb::appendCodePoint);
		return sb.toString();
	}

	private static Map<String, Integer> bigrams(String text) {
		int[] cps = text.codePoints().toArray();
		Map<String, Integer> grams = new HashMap<>();
		for (int i = 0; i + 1 < cps.length; i++) {
			grams.merge(new String(cps, i, 2), 1, Integer::sum);
		}
		return grams;
	}
}
