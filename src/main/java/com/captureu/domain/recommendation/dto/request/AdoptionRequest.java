package com.captureu.domain.recommendation.dto.request;

import jakarta.validation.constraints.NotNull;

/** @param hookIndex 첫 줄 후보 번호 (0부터) */
public record AdoptionRequest(@NotNull(message = "고를 첫 줄 후보 번호가 필요해요") Integer hookIndex) {
}
