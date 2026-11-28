package com.captureu.domain.tracking.dto.response;

import java.time.Instant;

/**
 * 추적 중인 게시물 하나의 가장 최근 시점 값과, 과거 게시물의 같은 나이 값(중앙값)과의 비교.
 *
 * @param checkpointHours 비교한 나이 (게시 후 N시간)
 * @param baselineViews   과거 게시물들의 같은 나이 조회수 중앙값. 비교할 게시물이 부족하면 null
 * @param baselineCount   비교에 쓴 과거 게시물 수
 * @param multiple        조회수 / baselineViews
 * @param surge           평소의 2배 이상 (초반 급상승)
 */
public record TrackedPostResponse(String mediaId, String permalink, String captionFirstLine, Instant postedAt,
		int checkpointHours, Integer views, Double skipRate, Double baselineViews, int baselineCount,
		Double multiple, boolean surge) {
}
