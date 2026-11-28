package com.captureu.domain.analysis.exception;

/** 분석할 인스타 계정이 설정되지 않음. */
public class AccountNotConnectedException extends RuntimeException {

	public AccountNotConnectedException() {
		super("연결된 인스타 계정 ID가 없습니다 (.env의 IG_USER_ID)");
	}
}
