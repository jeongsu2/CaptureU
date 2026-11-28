package com.captureu.domain.recommendation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param topic 다음 게시물 주제 한 줄. 예: "겨울 출근룩". 첫 줄 후보가 40자를 넘지 않게 40자까지 */
public record RecommendationRequest(@NotBlank(message = "주제를 한 줄로 넣어 주세요") @Size(max = 40, message = "주제는 40자까지 넣을 수 있어요") String topic) {
}
