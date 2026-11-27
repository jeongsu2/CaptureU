package com.captureu.recommendation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.captureu.analysis.CaptionHook.HookType;
import com.captureu.common.Texts;
import com.captureu.thumbnail.ThumbnailService;
import com.captureu.thumbnail.ThumbnailService.ThumbnailSignal;
import com.captureu.recommendation.PatternAnalyzer.HookPattern;
import com.captureu.recommendation.PatternAnalyzer.PatternSummary;
import com.captureu.recommendation.RecommendationDraft.HookCandidate;
import com.captureu.recommendation.RecommendationDraft.ThumbnailPlan;

/**
 * 규칙 기반 추천. LLM을 쓸 수 없을 때(키 없음, 호출 실패)도 추천이 나오게 한다.
 * 잘된 유형 순서대로 유형별 문장 틀에 주제를 넣는다. 주제 뒤에 조사를 붙이지 않는 틀만 쓴다 (받침에 따라 틀려서).
 */
final class TemplateHookWriter {

	static final String VERSION = "template-v1";
	static final int MAX_HOOKS = 5;
	private static final int MAX_TYPES = 3;
	private static final int OVERLAY_TOPIC_LENGTH = 10;

	/** 패턴이 없을 때 쓰는 기본 유형. 여러 계정에서 흔히 잘되는 순. */
	private static final List<HookType> DEFAULT_TYPES = List.of(HookType.QUESTION, HookType.LIST, HookType.HOW_TO);

	private static final Map<HookType, List<String>> TEMPLATES = Map.of(
			HookType.QUESTION, List.of("%s, 아직도 이렇게 하세요?", "%s 이거 저만 몰랐나요?"),
			HookType.TARGET, List.of("%s 고민인 분들 모여보세요", "%s 필독! 이것만 보세요"),
			HookType.LIST, List.of("%s 3가지 모음.zip", "%s TOP 3"),
			HookType.HOW_TO, List.of("%s 실패 없는 꿀팁", "%s 꿀팁 정리"),
			HookType.STORY, List.of("%s 솔직 후기", "%s 실패담 들려드릴게요"),
			HookType.ANNOUNCE, List.of("%s 드디어 공개합니다", "%s 커밍순"));

	private static final Map<HookType, String> OVERLAY_SUFFIX = Map.of(HookType.QUESTION, "?", HookType.TARGET,
			" 필독", HookType.LIST, " 3가지", HookType.HOW_TO, " 꿀팁", HookType.STORY, " 후기", HookType.ANNOUNCE,
			" 공개");

	/** 잘된 썸네일 특징(#11)별 구성 지시. */
	private static final Map<ThumbnailService.Feature, String> INSTRUCTIONS = new java.util.EnumMap<>(Map.ofEntries(
			Map.entry(ThumbnailService.Feature.FACE_CLOSE_UP, "얼굴을 화면의 1/3 이상으로 크게 잡아요."),
			Map.entry(ThumbnailService.Feature.LARGE_TEXT, "제목 글씨를 화면 폭 절반 이상으로 크게 넣어요."),
			Map.entry(ThumbnailService.Feature.ANY_TEXT, "핵심 문구를 짧은 글씨로 넣어요."),
			Map.entry(ThumbnailService.Feature.NO_PERSON, "사람 없이 제품이나 장면 위주로 찍어요."),
			Map.entry(ThumbnailService.Feature.TONE_BRIGHT, "밝고 화사한 색감으로 맞춰요."),
			Map.entry(ThumbnailService.Feature.TONE_DARK, "어둡고 무드 있는 색감으로 맞춰요."),
			Map.entry(ThumbnailService.Feature.TONE_PASTEL, "연한 파스텔 색감으로 맞춰요."),
			Map.entry(ThumbnailService.Feature.TONE_VIVID, "채도 높은 강렬한 색감으로 맞춰요."),
			Map.entry(ThumbnailService.Feature.CENTER_SUBJECT, "인물이나 제품 하나를 가운데에 둬요."),
			Map.entry(ThumbnailService.Feature.FULL_BODY, "전신이 다 보이게 찍어요."),
			Map.entry(ThumbnailService.Feature.FLAT_LAY, "제품을 위에서 내려다본 구도로 놓아요."),
			Map.entry(ThumbnailService.Feature.SPLIT, "전후 비교처럼 화면을 나눠요."),
			Map.entry(ThumbnailService.Feature.TEXT_CARD, "글자가 주인공인 카드처럼 만들어요.")));

	private TemplateHookWriter() {
	}

	static RecommendationDraft write(String topic, PatternSummary summary) {
		return write(topic, summary, List.of());
	}

	static RecommendationDraft write(String topic, PatternSummary summary, List<ThumbnailSignal> thumbnails) {
		Map<HookType, String> rationales = new LinkedHashMap<>();
		boolean singleUsed = false;
		for (HookPattern p : summary.patterns()) {
			if (rationales.size() == MAX_TYPES) {
				break;
			}
			// 한 계정 스타일은 많아야 1개만 쓴다 (한 계정에 치우치지 않게)
			if (p.scope() == PatternAnalyzer.Scope.SINGLE) {
				if (singleUsed) {
					continue;
				}
				singleUsed = true;
			}
			rationales.putIfAbsent(p.type(), PatternAnalyzer.evidence(p, summary.referenceCount()) + " 틀로 만들었어요.");
		}
		for (HookType type : DEFAULT_TYPES) {
			if (rationales.size() == MAX_TYPES) {
				break;
			}
			rationales.putIfAbsent(type, "분석에서 뚜렷한 근거가 없어 자주 쓰이는 " + PatternAnalyzer.label(type) + "으로 만들었어요.");
		}

		List<HookCandidate> hooks = new ArrayList<>();
		// 유형마다 첫 번째 틀을 먼저 채우고, 남는 자리를 두 번째 틀로 채운다 (한 유형에 쏠리지 않게)
		for (int round = 0; round < 2 && hooks.size() < MAX_HOOKS; round++) {
			for (Map.Entry<HookType, String> e : rationales.entrySet()) {
				if (hooks.size() == MAX_HOOKS) {
					break;
				}
				String template = TEMPLATES.get(e.getKey()).get(round);
				hooks.add(new HookCandidate(template.formatted(topic), e.getKey(), e.getValue()));
			}
		}

		HookType top = rationales.keySet().iterator().next();
		String overlay = shortTopic(topic) + OVERLAY_SUFFIX.get(top);
		String layout = "첫 프레임 위쪽 1/3에 굵은 글씨 1~2줄(한 줄 8자 안팎), 인물이나 제품은 가운데. "
				+ "프로필 그리드(3:4)에서 잘리지 않게 글자는 가운데 정렬.";
		String rationale = "썸네일 이미지 분석 결과가 없어 일반 원칙으로 제안했어요. 1순위 첫 줄(" + PatternAnalyzer.label(top)
				+ ")과 같은 약속을 짧게 넣었어요.";
		if (thumbnails != null && !thumbnails.isEmpty()) {
			List<ThumbnailSignal> used = thumbnails.stream().limit(2).toList();
			layout = used.stream().map(s -> INSTRUCTIONS.get(s.feature())).collect(java.util.stream.Collectors.joining(" "))
					+ " 글자는 프로필 그리드(3:4)에서 잘리지 않게 가운데 정렬.";
			rationale = used.get(0).evidence() + "을 따랐어요.";
		}
		return new RecommendationDraft(List.copyOf(hooks), new ThumbnailPlan(overlay, layout, rationale));
	}

	/** 썸네일에 넣을 만큼 주제를 줄인다. 단어 중간에서 자르지 않고, 첫 단어부터 길면 그 단어만 자른다. */
	static String shortTopic(String topic) {
		StringBuilder sb = new StringBuilder();
		for (String word : topic.strip().split("\\s+")) {
			int length = sb.isEmpty() ? word.codePointCount(0, word.length())
					: sb.codePointCount(0, sb.length()) + 1 + word.codePointCount(0, word.length());
			if (length > OVERLAY_TOPIC_LENGTH) {
				break;
			}
			if (!sb.isEmpty()) {
				sb.append(' ');
			}
			sb.append(word);
		}
		return sb.isEmpty() ? Texts.truncate(topic.strip(), OVERLAY_TOPIC_LENGTH) : sb.toString();
	}
}
