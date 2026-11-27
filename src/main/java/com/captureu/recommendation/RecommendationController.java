package com.captureu.recommendation;

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

import com.captureu.recommendation.RecommendationService.AdoptionResponse;
import com.captureu.recommendation.RecommendationService.RecommendationResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 썸네일 구성안·첫 줄 후보 추천 API (#13). */
@RestController
@Validated
class RecommendationController {

	private final RecommendationService recommendationService;

	RecommendationController(RecommendationService recommendationService) {
		this.recommendationService = recommendationService;
	}

	/** 분석 결과를 바탕으로 다음 게시물 주제에 맞는 추천을 만든다. 같은 분석에 여러 번 요청할 수 있다. */
	@PostMapping("/api/analyses/{analysisId}/recommendations")
	ResponseEntity<RecommendationResponse> recommend(@PathVariable Long analysisId,
			@Valid @RequestBody RecommendationRequest request) {
		RecommendationResponse response = recommendationService.recommend(analysisId, request.topic());
		return ResponseEntity.created(URI.create("/api/recommendations/" + response.id())).body(response);
	}

	@GetMapping("/api/recommendations/{id}")
	RecommendationResponse get(@PathVariable Long id) {
		return recommendationService.get(id);
	}

	/** 첫 줄 후보 하나를 채택한다. 예: {"hookIndex": 0} */
	@PostMapping("/api/recommendations/{id}/adoptions")
	ResponseEntity<AdoptionResponse> adopt(@PathVariable Long id, @Valid @RequestBody AdoptionRequest request) {
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

	/** @param topic 다음 게시물 주제 한 줄. 예: "겨울 출근룩". 첫 줄 후보가 40자를 넘지 않게 40자까지 */
	record RecommendationRequest(@NotBlank(message = "주제를 한 줄로 넣어 주세요") @Size(max = 40, message = "주제는 40자까지 넣을 수 있어요") String topic) {
	}

	/** @param hookIndex 첫 줄 후보 번호 (0부터) */
	record AdoptionRequest(@NotNull(message = "고를 첫 줄 후보 번호가 필요해요") Integer hookIndex) {
	}
}
