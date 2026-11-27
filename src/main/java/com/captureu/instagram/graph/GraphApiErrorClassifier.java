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

	/** 100번 중 "객체가 없거나 권한이 없음". */
	private static final int SUBCODE_OBJECT_NOT_FOUND = 33;

	private GraphApiErrorClassifier() {
	}

	public static GraphApiErrorType classify(int httpStatus, Integer code, Integer subcode) {
		return classify(httpStatus, code, subcode, false);
	}

	/**
	 * @param isTransient Meta가 error.is_transient로 "잠시 후 다시 시도하면 될 수 있음"을 알려 준 경우
	 */
	public static GraphApiErrorType classify(int httpStatus, Integer code, Integer subcode, boolean isTransient) {
		if (code != null) {
			if (RATE_LIMIT_CODES.contains(code)) {
				return GraphApiErrorType.RATE_LIMITED;
			}
			if (TOKEN_INVALID_CODES.contains(code)) {
				return GraphApiErrorType.TOKEN_INVALID;
			}
			if (TEMPORARY_CODES.contains(code) || isTransient) {
				return GraphApiErrorType.TEMPORARY;
			}
			if (code == 10 || (code >= 200 && code <= 299)) {
				return GraphApiErrorType.PERMISSION_DENIED;
			}
			if (code == 110 || (code == 100 && subcode != null && subcode == SUBCODE_OBJECT_NOT_FOUND)) {
				return GraphApiErrorType.TARGET_NOT_FOUND;
			}
			if (code == 100) {
				return GraphApiErrorType.INVALID_REQUEST;
			}
		}
		if (httpStatus == 429) {
			return GraphApiErrorType.RATE_LIMITED;
		}
		if (httpStatus >= 500 || isTransient) {
			return GraphApiErrorType.TEMPORARY;
		}
		return GraphApiErrorType.UNKNOWN;
	}
}
