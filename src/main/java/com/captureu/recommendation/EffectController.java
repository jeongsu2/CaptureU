package com.captureu.recommendation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.captureu.recommendation.EffectService.EffectReport;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 추천 효과 검증 API (#14). */
@RestController
class EffectController {

	private final EffectService effectService;

	EffectController(EffectService effectService) {
		this.effectService = effectService;
	}

	/** 이 분석 시점 게시물로 채택한 문구를 쓴 게시물을 찾고, 내 중앙값과 비교한다. */
	@GetMapping("/api/analyses/{analysisId}/effects")
	EffectReport effects(@PathVariable Long analysisId) {
		return effectService.effects(analysisId);
	}

	/** "이 게시물에 썼어요"(used=true) / "아니에요"(used=false). 예: {"analysisId":3,"mediaId":"179...","used":true} */
	@PostMapping("/api/adoptions/{adoptionId}/match")
	ResponseEntity<Void> decide(@PathVariable Long adoptionId, @Valid @RequestBody MatchRequest request) {
		effectService.decide(adoptionId, request.analysisId(), request.mediaId(), request.used());
		return ResponseEntity.noContent().build();
	}

	@ExceptionHandler(AdoptionNotFoundException.class)
	ProblemDetail notFound(AdoptionNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail invalid(IllegalArgumentException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	record MatchRequest(@NotNull(message = "분석 ID가 필요해요") Long analysisId,
			@NotBlank(message = "게시물 ID가 필요해요") @Size(max = 32) String mediaId,
			@NotNull(message = "썼는지 여부가 필요해요") Boolean used) {
	}
}
