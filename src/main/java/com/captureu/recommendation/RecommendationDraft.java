package com.captureu.recommendation;

import java.util.List;

import com.captureu.analysis.CaptionHook.HookType;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 추천 본문: 첫 줄 후보와 썸네일 구성안. LLM 구조화 출력의 스키마로도 쓴다 (설명은 Claude가 읽는다).
 */
public record RecommendationDraft(
		@JsonPropertyDescription("캡션 첫 줄 후보 5개. 우선순위가 높은 유형부터") List<HookCandidate> hooks,
		@JsonPropertyDescription("썸네일 구성안 1개") ThumbnailPlan thumbnail) {

	public record HookCandidate(
			@JsonPropertyDescription("캡션 첫 줄. 40자 이내, 해시태그·광고 표시·멘션 없이") String text,
			@JsonPropertyDescription("이 후보가 따른 첫 줄 유형") HookType type,
			@JsonPropertyDescription("어떤 근거(계정, N개 중 M개)를 따랐는지 한 문장. 주어진 숫자만 사용") String rationale) {
	}

	public record ThumbnailPlan(
			@JsonPropertyDescription("썸네일에 넣을 글자. 2줄 이내, 한 줄 8자 안팎") String overlayText,
			@JsonPropertyDescription("글자 위치·크기, 인물이나 제품 배치를 구체적으로") String layout,
			@JsonPropertyDescription("근거 한 문장") String rationale) {
	}
}
