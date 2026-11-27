package com.captureu.analysis;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 분석 요청 API. 지금은 .env에 설정한 계정 하나만 분석한다 (사용자 로그인은 #16).
 * 수집 실패도 분석 기록으로 남기므로 201과 함께 status=FAILED, failureType을 돌려준다.
 */
@RestController
@RequestMapping("/api/analyses")
class AnalysisController {

	private final AnalysisService analysisService;

	AnalysisController(AnalysisService analysisService) {
		this.analysisService = analysisService;
	}

	@PostMapping
	ResponseEntity<AnalysisResponse> request() {
		AnalysisResponse response = analysisService.requestForConnectedAccount();
		return ResponseEntity.created(URI.create("/api/analyses/" + response.id())).body(response);
	}

	@GetMapping("/{id}")
	AnalysisResponse get(@PathVariable Long id) {
		return analysisService.get(id);
	}

	@ExceptionHandler(AnalysisNotFoundException.class)
	ProblemDetail notFound(AnalysisNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
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
