package com.captureu.domain.analysis.exception;

public class AnalysisNotFoundException extends RuntimeException {

	public AnalysisNotFoundException(Long id) {
		super("분석 요청을 찾을 수 없습니다: " + id);
	}
}
