package com.captureu.analysis;

/** 분석이 아직 끝나지 않았거나 실패해서 결과를 계산할 수 없음. */
public class AnalysisNotReadyException extends RuntimeException {

	public AnalysisNotReadyException(Long id, AnalysisStatus status) {
		super("분석 %d은(는) 아직 결과를 볼 수 없는 상태입니다: %s".formatted(id, status));
	}
}
