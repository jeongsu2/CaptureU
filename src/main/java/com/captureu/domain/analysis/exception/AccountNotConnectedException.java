package com.captureu.domain.analysis.exception;

/** 분석할 인스타 계정이 설정되지 않음. */
public class AccountNotConnectedException extends RuntimeException {

	public AccountNotConnectedException() {
		super("인스타 계정으로 로그인해 주세요");
	}
}
