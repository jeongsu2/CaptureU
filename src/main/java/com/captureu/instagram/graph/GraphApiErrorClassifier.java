package com.captureu.instagram.graph;

import java.util.Set;

/**
 * HTTP 상태와 Graph API 에러 코드로 {@link GraphApiErrorType}을 정한다.
 * 코드 값은 Meta 문서의 공통 에러 코드와 스파이크(docs/spike-meta-api.md)에서 실제로 받은 값을 기준으로 한다.
 */
public final class GraphApiErrorClassifier {

	/** 앱·사용자·페이지·사용 사례별 호출 한도 (80002는 Instagram 사용 사례 한도). */
	private static final Set<Integer> RATE_LIMIT_CODES = Set.of(4, 17, 32, 341, 613, 80002);

	/** Meta 쪽 일시 오류. */
	private static final Set<Integer> TEMPORARY_CODES = Set.of(1, 2);

	private static final Set<Integer> TOKEN_INVALID_CODES = Set.of(102, 190);

	private GraphApiErrorClassifier() {
	}

	public static GraphApiErrorType classify(int httpStatus, Integer code, Integer subcode) {
		if (code != null) {
			if (RATE_LIMIT_CODES.contains(code)) {
				return GraphApiErrorType.RATE_LIMITED;
			}
			if (TEMPORARY_CODES.contains(code)) {
				return GraphApiErrorType.TEMPORARY;
			}
			if (TOKEN_INVALID_CODES.contains(code)) {
				return GraphApiErrorType.TOKEN_INVALID;
			}
			if (code == 10 || (code >= 200 && code <= 299)) {
				return GraphApiErrorType.PERMISSION_DENIED;
			}
			if (code == 110) {
				return GraphApiErrorType.TARGET_NOT_FOUND;
			}
			if (code == 100) {
				return GraphApiErrorType.INVALID_REQUEST;
			}
		}
		if (httpStatus == 429) {
			return GraphApiErrorType.RATE_LIMITED;
		}
		if (httpStatus >= 500) {
			return GraphApiErrorType.TEMPORARY;
		}
		return GraphApiErrorType.UNKNOWN;
	}
}
