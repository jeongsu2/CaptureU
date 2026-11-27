package com.captureu.thumbnail;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** 썸네일 한 장의 특징. LLM 구조화 출력의 스키마로도 쓴다 (설명은 Claude가 읽는다). */
public record ThumbnailTags(
		@JsonPropertyDescription("사람 얼굴이 화면의 큰 부분(대략 1/4 이상)을 차지하는 클로즈업인지") boolean faceCloseUp,
		@JsonPropertyDescription("보이는 사람 수. 없으면 0") int personCount,
		@JsonPropertyDescription("화면에 얹은 글자 크기. 없으면 NONE, 화면 폭의 절반 이상을 가로지르는 큰 제목이면 LARGE") TextSize textSize,
		@JsonPropertyDescription("화면에 얹은 글자를 그대로 30자 이내로. 없으면 빈 문자열") String textContent,
		@JsonPropertyDescription("전체 색감") Tone tone,
		@JsonPropertyDescription("구도") Composition composition) {

	public enum TextSize {
		NONE, SMALL, LARGE
	}

	public enum Tone {
		/** 밝고 화사함 */
		BRIGHT,
		/** 어둡거나 무드 있는 */
		DARK,
		/** 파스텔·연한 */
		PASTEL,
		/** 채도 높고 강렬 */
		VIVID,
		/** 무채색·차분 */
		NEUTRAL
	}

	public enum Composition {
		/** 인물이나 제품 하나가 가운데 */
		CENTER_SUBJECT,
		/** 전신 */
		FULL_BODY,
		/** 위에서 내려다본 제품 배치 */
		FLAT_LAY,
		/** 전후 비교·여러 장 나눔 */
		SPLIT,
		/** 글자가 주인공인 카드 */
		TEXT_CARD,
		/** 장소·풍경 */
		SCENE,
		OTHER
	}
}
