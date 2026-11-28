package com.captureu.domain.analysis.util;

import com.captureu.global.util.Texts;

/**
 * 캡션 첫 줄. 피드에서 보이는 부분이라 후킹 역할을 한다.
 * #광고, (저장,공유✅) 같은 머리말을 떼는 전처리는 첫 줄 유형 분류(#12)에서 한다.
 */
public final class CaptionText {

	static final int MAX_LENGTH = 500;

	private CaptionText() {
	}

	public static String firstLine(String caption) {
		if (caption == null) {
			return null;
		}
		for (String line : caption.split("\\R")) {
			String trimmed = line.strip();
			if (!trimmed.isEmpty()) {
				return Texts.truncate(trimmed, MAX_LENGTH);
			}
		}
		return null;
	}
}
