package com.captureu.domain.recommendation.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.captureu.domain.analysis.service.AnalysisAccessGuard;
import com.captureu.domain.recommendation.dto.request.MatchRequest;
import com.captureu.domain.recommendation.exception.AdoptionConflictException;
import com.captureu.domain.recommendation.exception.AdoptionNotFoundException;
import com.captureu.domain.recommendation.exception.InvalidMatchException;
import com.captureu.domain.recommendation.service.EffectService;
import com.captureu.domain.recommendation.service.EffectService.EffectReport;

import jakarta.validation.Valid;

/** 추천 효과 검증 API (#14). */
@RestController
public class EffectController {

	private final EffectService effectService;
	private final AnalysisAccessGuard guard;

	EffectController(EffectService effectService, AnalysisAccessGuard guard) {
		this.effectService = effectService;
		this.guard = guard;
	}

	/** 이 분석 시점 게시물로 채택한 문구를 쓴 게시물을 찾고, 내 중앙값과 비교한다. */
	@GetMapping("/api/analyses/{analysisId}/effects")
	EffectReport effects(@PathVariable Long analysisId) {
		guard.check(analysisId);
		return effectService.effects(analysisId);
	}

	/** "이 게시물에 썼어요"(used=true) / "아니에요"(used=false). 예: {"analysisId":3,"mediaId":"179...","used":true} */
	@PostMapping("/api/adoptions/{adoptionId}/match")
	ResponseEntity<Void> decide(@PathVariable Long adoptionId, @Valid @RequestBody MatchRequest request) {
		guard.check(request.analysisId());
		effectService.decide(adoptionId, request.analysisId(), request.mediaId(), request.used());
		return ResponseEntity.noContent().build();
	}

	@ExceptionHandler(AdoptionNotFoundException.class)
	ProblemDetail notFound(AdoptionNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler(InvalidMatchException.class)
	ProblemDetail invalid(InvalidMatchException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler(AdoptionConflictException.class)
	ProblemDetail conflict(AdoptionConflictException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	/** 같은 채택을 동시에 바꾼 경우 (version). */
	@ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
	ProblemDetail concurrent(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "다른 곳에서 먼저 바꿨어요. 새로고침 후 다시 시도해 주세요");
	}
}
