package com.captureu.domain.tracking.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.captureu.domain.tracking.dto.request.TrackingRequest;
import com.captureu.domain.tracking.dto.response.TrackingResponse;
import com.captureu.domain.tracking.service.TrackingService;

import jakarta.validation.Valid;

/** '더 이어서 하기' 신청·해제와 추적 결과 (#5). */
@RestController
@RequestMapping("/api/tracking")
public class TrackingController {

	private final TrackingService trackingService;

	public TrackingController(TrackingService trackingService) {
		this.trackingService = trackingService;
	}

	@GetMapping
	public TrackingResponse status() {
		return trackingService.status();
	}

	@PutMapping
	public TrackingResponse change(@Valid @RequestBody TrackingRequest request) {
		return trackingService.change(request.enabled());
	}
}
