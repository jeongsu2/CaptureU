package com.captureu.domain.recommendation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MatchRequest(@NotNull(message = "분석 ID가 필요해요") Long analysisId,
		@NotBlank(message = "게시물 ID가 필요해요") @Size(max = 32) String mediaId,
		@NotNull(message = "썼는지 여부가 필요해요") Boolean used) {
}
