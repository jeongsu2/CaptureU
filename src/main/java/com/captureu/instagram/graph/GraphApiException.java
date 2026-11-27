package com.captureu.instagram.graph;

/**
 * Graph API 호출 실패. 메시지에는 토큰이 들어가지 않는다 ({@link TokenMasker}로 가린 뒤 만든다).
 */
public class GraphApiException extends RuntimeException {

	private final GraphApiErrorType type;
	private final int httpStatus;
	private final Integer code;
	private final Integer subcode;
	private final String detail;
	private final int attempts;

	public GraphApiException(GraphApiErrorType type, int httpStatus, Integer code, Integer subcode,
			String maskedDetail) {
		this(type, httpStatus, code, subcode, maskedDetail, 1);
	}

	private GraphApiException(GraphApiErrorType type, int httpStatus, Integer code, Integer subcode,
			String maskedDetail, int attempts) {
		super("[%s] http=%d code=%s subcode=%s attempts=%d: %s".formatted(type, httpStatus, code, subcode,
				attempts, maskedDetail));
		this.type = type;
		this.httpStatus = httpStatus;
		this.code = code;
		this.subcode = subcode;
		this.detail = maskedDetail;
		this.attempts = attempts;
	}

	/** 재시도 끝에 실패했을 때 시도 횟수를 붙인 사본. */
	GraphApiException withAttempts(int attempts) {
		return new GraphApiException(type, httpStatus, code, subcode, detail, attempts);
	}

	public GraphApiErrorType getType() {
		return type;
	}

	public int getHttpStatus() {
		return httpStatus;
	}

	public Integer getCode() {
		return code;
	}

	public Integer getSubcode() {
		return subcode;
	}

	public String getDetail() {
		return detail;
	}

	public int getAttempts() {
		return attempts;
	}

	public boolean isRetryable() {
		return type.isRetryable();
	}
}
