package com.captureu.profile;

import java.util.Arrays;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.captureu.profile.ProfileService.Concepts;

/** 크리에이터 컨셉 API (#15). */
@RestController
@RequestMapping("/api/profile")
class ProfileController {

	private final ProfileService profileService;

	ProfileController(ProfileService profileService) {
		this.profileService = profileService;
	}

	/** 고를 수 있는 컨셉 목록 (코드와 한글 이름). */
	@GetMapping("/concepts")
	List<ConceptOption> concepts() {
		return Arrays.stream(Concept.values()).map(c -> new ConceptOption(c, c.label())).toList();
	}

	/** 아직 고르지 않았으면 204. */
	@GetMapping
	ResponseEntity<Concepts> get() {
		return profileService.current().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
	}

	/** 예: {"main": "BEAUTY", "sub": "FASHION"}. sub는 없어도 된다. */
	@PutMapping
	Concepts change(@RequestBody Concepts request) {
		return profileService.change(request.main(), request.sub());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail invalid(IllegalArgumentException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	record ConceptOption(Concept code, String label) {
	}
}
