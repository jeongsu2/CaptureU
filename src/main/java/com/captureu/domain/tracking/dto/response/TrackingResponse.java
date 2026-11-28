package com.captureu.domain.tracking.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * @param enabled   추적 중인지
 * @param startedAt 마지막으로 켠 시각. 켠 적 없으면 null
 * @param posts     최근 게시물부터
 */
public record TrackingResponse(boolean enabled, Instant startedAt, List<TrackedPostResponse> posts) {
}
