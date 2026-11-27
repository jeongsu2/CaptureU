package com.captureu.analysis;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 분석 관련 예외 응답. 분석 결과를 쓰는 다른 API(추천 등)도 같은 상태 코드를 돌려준다. */
@RestControllerAdvice
class AnalysisExceptionHandler {

	@ExceptionHandler(AnalysisNotFoundException.class)
	ProblemDetail notFound(AnalysisNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	/** 수집 중이면 409(잠시 후 다시), 실패한 분석이면 422(다시 봐도 결과 없음). */
	@ExceptionHandler(AnalysisNotReadyException.class)
	ProblemDetail notReady(AnalysisNotReadyException e) {
		HttpStatus status = e.getStatus() == AnalysisStatus.FAILED ? HttpStatus.UNPROCESSABLE_CONTENT
				: HttpStatus.CONFLICT;
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
		problem.setProperty("analysisStatus", e.getStatus().name());
		return problem;
	}

	@ExceptionHandler(AccountNotConnectedException.class)
	ProblemDetail notConnected(AccountNotConnectedException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler(AnalysisInProgressException.class)
	ProblemDetail inProgress(AnalysisInProgressException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}
}
