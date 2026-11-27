package com.captureu.instagram.graph;

/**
 * Graph API 에러를 "어떻게 처리할지" 기준으로 나눈 분류.
 * 바로 다시 시도해도 되는 에러와 그렇지 않은 에러를 섞지 않는 것이 목적이다.
 */
public enum GraphApiErrorType {

	/**
	 * 호출 한도 초과. 보통 몇 분 이상 지속되므로 몇 초 간격으로 다시 시도하면 한도만 더 쓴다.
	 * 클라이언트는 바로 재시도하지 않고, 호출자가 {@link GraphApiUsage#regainAccessMinutes()} 이후에 다시 시도한다.
	 */
	RATE_LIMITED(false),

	/** 일시 장애(5xx, 네트워크 오류, Meta가 is_transient로 알려 준 오류). 바로 다시 시도한다. */
	TEMPORARY(true),

	/** 토큰 무효. 예: 사용자가 비즈니스 설정에서 앱 연결을 끊음(190/464). 재연결을 안내한다. */
	TOKEN_INVALID(false),

	/** 권한 없음. 필요한 권한을 받지 않았거나 회수됨. */
	PERMISSION_DENIED(false),

	/** 조회 대상이 없거나 개인 계정(110/2207013), 또는 없는 객체(100/33). */
	TARGET_NOT_FOUND(false),

	/** 잘못된 요청(필드, 지표 이름 등). 코드를 고쳐야 한다. */
	INVALID_REQUEST(false),

	/** 분류되지 않은 에러. 재시도하지 않는다. */
	UNKNOWN(false);

	private final boolean retryable;

	GraphApiErrorType(boolean retryable) {
		this.retryable = retryable;
	}

	/** 바로(몇 초 안에) 다시 시도해도 되는지. */
	public boolean isRetryable() {
		return retryable;
	}
}
