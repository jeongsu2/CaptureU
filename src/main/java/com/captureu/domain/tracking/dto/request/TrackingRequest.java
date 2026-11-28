package com.captureu.domain.tracking.dto.request;

import jakarta.validation.constraints.NotNull;

/** @param enabled 추적을 켤지 끌지 */
public record TrackingRequest(@NotNull(message = "켤지 끌지 알려 주세요") Boolean enabled) {
}
