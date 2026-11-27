package com.captureu.analysis;

/**
 * 분석 결과를 계산할 수 없음. 수집 중이면 잠시 후 다시 보면 되지만, 실패한 분석은 다시 봐도 결과가 없다.
 * 클라이언트가 둘을 구분하도록 상태를 함께 담는다.
 */
public class AnalysisNotReadyException extends RuntimeException {

	private final AnalysisStatus status;

	public AnalysisNotReadyException(Long id, AnalysisStatus status) {
		super(status == AnalysisStatus.FAILED ? "분석 %d은(는) 실패해서 결과가 없습니다. 다시 분석해 주세요".formatted(id)
				: "분석 %d은(는) 아직 수집 중입니다. 잠시 후 다시 확인해 주세요".formatted(id));
		this.status = status;
	}

	public AnalysisStatus getStatus() {
		return status;
	}
}
