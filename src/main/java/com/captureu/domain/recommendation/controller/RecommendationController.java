package com.captureu.domain.recommendation.controller;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.captureu.domain.analysis.service.AnalysisAccessGuard;
import com.captureu.domain.recommendation.dto.request.AdoptionRequest;
import com.captureu.domain.recommendation.dto.request.RecommendationRequest;
import com.captureu.domain.recommendation.exception.InvalidHookIndexException;
import com.captureu.domain.recommendation.exception.InvalidTopicException;
import com.captureu.domain.recommendation.exception.RecommendationNotFoundException;
import com.captureu.domain.recommendation.service.RecommendationService;
import com.captureu.domain.recommendation.service.RecommendationService.AdoptionResponse;
import com.captureu.domain.recommendation.service.RecommendationService.RecommendationResponse;

import jakarta.validation.Valid;

/** 썸네일 구성안·첫 줄 후보 추천 API (#13). */
@RestController
@Validated
public class RecommendationController {

	private final RecommendationService recommendationService;
	private final AnalysisAccessGuard guard;

	RecommendationController(RecommendationService recommendationService, AnalysisAccessGuard guard) {
		this.recommendationService = recommendationService;
		this.guard = guard;
	}

	/** 분석 결과를 바탕으로 다음 게시물 주제에 맞는 추천을 만든다. 같은 분석에 여러 번 요청할 수 있다. */
	@PostMapping("/api/analyses/{analysisId}/recommendations")
	ResponseEntity<RecommendationResponse> recommend(@PathVariable Long analysisId,
			@Valid @RequestBody RecommendationRequest request) {
		guard.check(analysisId);
		RecommendationResponse response = recommendationService.recommend(analysisId, request.topic());
		return ResponseEntity.created(URI.create("/api/recommendations/" + response.id())).body(response);
	}

	@GetMapping("/api/recommendations/{id}")
	RecommendationResponse get(@PathVariable Long id) {
		guard.check(recommendationService.analysisIdOf(id));
		return recommendationService.get(id);
	}

	/** 첫 줄 후보 하나를 채택한다. 예: {"hookIndex": 0} */
	@PostMapping("/api/recommendations/{id}/adoptions")
	ResponseEntity<AdoptionResponse> adopt(@PathVariable Long id, @Valid @RequestBody AdoptionRequest request) {
		guard.check(recommendationService.analysisIdOf(id));
		AdoptionResponse response = recommendationService.adopt(id, request.hookIndex());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@ExceptionHandler(InvalidHookIndexException.class)
	ProblemDetail invalidIndex(InvalidHookIndexException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler(RecommendationNotFoundException.class)
	ProblemDetail notFound(RecommendationNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler(InvalidTopicException.class)
	ProblemDetail invalidTopic(InvalidTopicException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}
}
