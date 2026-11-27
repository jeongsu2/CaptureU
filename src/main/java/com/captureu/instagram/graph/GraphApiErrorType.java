package com.captureu.instagram.graph;

/**
 * Graph API 에러를 "어떻게 처리할지" 기준으로 나눈 분류.
 * 재시도해도 되는 에러와, 재시도하면 안 되는 에러를 섞지 않는 것이 목적이다.
 */
public enum GraphApiErrorType {

	/** 호출 한도 초과. 기다렸다가 다시 시도한다. */
	RATE_LIMITED(true),

	/** 일시 장애(5xx, 네트워크 오류, Meta 쪽 알 수 없는 오류). 다시 시도한다. */
	TEMPORARY(true),

	/** 토큰 무효. 예: 사용자가 비즈니스 설정에서 앱 연결을 끊음(190/464). 재시도하지 않고 재연결을 안내한다. */
	TOKEN_INVALID(false),

	/** 권한 없음. 필요한 권한을 받지 않았거나 회수됨. */
	PERMISSION_DENIED(false),

	/** 조회 대상 계정이 없거나 개인 계정(110/2207013). 둘은 구분되지 않는다. */
	TARGET_NOT_FOUND(false),

	/** 잘못된 요청(필드, 지표 이름 등). 코드를 고쳐야 한다. */
	INVALID_REQUEST(false),

	/** 분류되지 않은 에러. 재시도하지 않는다. */
	UNKNOWN(false);

	private final boolean retryable;

	GraphApiErrorType(boolean retryable) {
		this.retryable = retryable;
	}

	public boolean isRetryable() {
		return retryable;
	}
}
