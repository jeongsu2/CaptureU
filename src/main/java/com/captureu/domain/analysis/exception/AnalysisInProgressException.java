package com.captureu.domain.analysis.exception;

/** 같은 계정의 분석이 이미 진행 중. */
public class AnalysisInProgressException extends RuntimeException {

	public AnalysisInProgressException(String igUserId) {
		super("이 계정의 분석이 이미 진행 중입니다. 끝난 뒤 다시 요청해 주세요: " + igUserId);
	}
}
