package com.captureu.analysis;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.captureu.common.Texts;

/**
 * 캡션 첫 줄을 "광고 표시 / 행동 유도 문구 / 진짜 후킹 문장"으로 나누고, 후킹 문장의 유형을 규칙으로 분류한다 (#12).
 * 규칙은 실제 크리에이터 캡션 첫 줄 표본을 보고 만들었다. 예:
 * <ul>
 * <li>"#광고 152 스트레이트체형의 여름 기본템 모음.zip" → 광고, 후킹 "152 스트레이트체형의 여름 기본템 모음.zip", 목록형</li>
 * <li>"(저장•공유) 여름에도 긴바지 못 잃는 여자들을 위한 영상" → 저장·공유 유도, 대상 지목형</li>
 * <li>"갑자기 여친이 이렇게 인생네컷 찍어오면 어떡할거야" → 질문형</li>
 * </ul>
 */
public record CaptionHook(String hookText, HookType type, boolean adDisclosure, CallToAction callToAction) {

	public enum HookType {
		/** 질문으로 끝나거나 묻는 말 */
		QUESTION,
		/** 특정 대상을 콕 집어 부름 (키 150-159, 키작녀, ~들 모여보세요, ~를 위한) */
		TARGET,
		/** 숫자·목록·모음 (top4, 3가지, .zip, 모음) */
		LIST,
		/** 방법·꿀팁·추천 */
		HOW_TO,
		/** 이야기·고백·후기 (실화, 실패담, 이별, ~한 일) */
		STORY,
		/** 공지·예고 (커밍슌, 오픈, 나왔습니다, D-3) */
		ANNOUNCE,
		OTHER
	}

	public enum CallToAction {
		NONE,
		/** 저장·공유해 달라는 머리말 */
		SAVE_SHARE,
		/** 할인·코드·쿠폰 머리말 */
		PROMOTION
	}

	static final int MAX_LENGTH = 500;

	private static final Pattern AD_TAG = Pattern.compile(
			"(#광고|#협찬|#제품제공|#유료광고|#ad\\b|#sponsored|\\*광고|\\[광고]|\\(광고\\)|\\[협찬]|\\(협찬\\)|\\[제품제공])",
			Pattern.CASE_INSENSITIVE);
	/** 맨 앞의 [ ... ] 또는 ( ... ) 머리말 */
	private static final Pattern LEADING_BLOCK = Pattern.compile("^\\s*([\\[(])([^\\])]{1,20})[\\])]\\s*");
	private static final Pattern SAVE_SHARE = Pattern.compile("저장|공유|팔로우");
	private static final Pattern PROMO = Pattern.compile("할인|쿠폰|코드|%|세일|특가");
	/** 끝에 붙은 해시태그·멘션 (#9월올영세일 @brand) */
	private static final Pattern TRAILING_TAGS = Pattern.compile("(\\s+[#@][^\\s]*)+\\s*$");
	private static final Pattern LEADING_SYMBOLS = Pattern.compile("^[\\s:;•·\\-–>~|]+");

	private static final Pattern QUESTION = Pattern.compile(
			"[?？]|어떡|어떻게|뭐가|뭘|왜 |누가|어디|할까|볼까|래요(?![가-힣])|실래요|나요(?![가-힣])|인가요|일까");
	private static final Pattern TARGET = Pattern.compile(
			"키\\s?1[4-7]\\d|키작|키큰|체형|모여|모여보세요|위한|필독|이라면|라면 |들에게|들을|공주님|여자들|남자들|커플들|학생|직장인|인프피|INFP|ENFP|ISTJ|ESTJ",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern LIST = Pattern.compile(
			"\\.zip|모음|top\\s?\\d|TOP\\s?\\d|\\d+\\s?가지|\\d+\\s?개(?!월)|\\d+\\s?선(?![가-힣])|라인업|best\\s?\\d|\\d+\\s?종(?![가-힣])",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern HOW_TO = Pattern.compile("법(?![가-힣])|하는 법|방법|꿀팁|팁|추천|루틴|치트키|꼼수|정리|가이드|하는법");
	private static final Pattern STORY = Pattern.compile(
			"실화|썰|실패담|후기|이별|헤어|고백|옛날|그날|벌어지는 일|있었던|했던|죄송|사실 |솔직");
	private static final Pattern ANNOUNCE = Pattern.compile(
			"커밍|오픈|나왔|출시|시작되었|시작합|업데이트|업뎃|만나요|D-\\d|예고|공개");

	public static CaptionHook parse(String firstLine) {
		if (firstLine == null || firstLine.isBlank()) {
			return new CaptionHook(null, HookType.OTHER, false, CallToAction.NONE);
		}
		String text = firstLine.strip();
		boolean ad = AD_TAG.matcher(text).find();
		text = AD_TAG.matcher(text).replaceAll(" ");

		CallToAction cta = CallToAction.NONE;
		Matcher block = LEADING_BLOCK.matcher(text);
		if (block.find()) {
			String inside = block.group(2);
			if (SAVE_SHARE.matcher(inside).find()) {
				cta = CallToAction.SAVE_SHARE;
				text = text.substring(block.end());
			}
			else if (PROMO.matcher(inside).find()) {
				cta = CallToAction.PROMOTION;
				text = text.substring(block.end());
			}
			// 그 외 머리말([+5cm] 등)은 후킹의 일부라 남긴다
		}
		if (cta == CallToAction.NONE && text.strip().startsWith("저장")) {
			cta = CallToAction.SAVE_SHARE;
		}

		text = TRAILING_TAGS.matcher(text).replaceAll("");
		text = LEADING_SYMBOLS.matcher(text).replaceAll("");
		text = text.replaceAll("\\s{2,}", " ").strip();
		String hook = text.isEmpty() ? null : Texts.truncate(text, MAX_LENGTH);
		return new CaptionHook(hook, classify(hook), ad, cta);
	}

	/** 우선순위: 질문 > 대상 지목 > 목록 > 방법 > 이야기 > 공지 > 기타. 한 문장에 여러 신호가 있으면 앞의 것을 고른다. */
	static HookType classify(String hook) {
		if (hook == null) {
			return HookType.OTHER;
		}
		String text = hook.toLowerCase(Locale.ROOT);
		if (QUESTION.matcher(text).find()) {
			return HookType.QUESTION;
		}
		if (TARGET.matcher(hook).find()) {
			return HookType.TARGET;
		}
		if (LIST.matcher(hook).find()) {
			return HookType.LIST;
		}
		if (HOW_TO.matcher(hook).find()) {
			return HookType.HOW_TO;
		}
		if (STORY.matcher(hook).find()) {
			return HookType.STORY;
		}
		if (ANNOUNCE.matcher(hook).find()) {
			return HookType.ANNOUNCE;
		}
		return HookType.OTHER;
	}
}
