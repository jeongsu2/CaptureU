package com.captureu.domain.thumbnail.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.captureu.domain.thumbnail.service.ThumbnailService;
import com.captureu.domain.thumbnail.service.ThumbnailService.TagResult;
import com.captureu.domain.thumbnail.service.ThumbnailService.ThumbnailComparison;

/** 썸네일 특징 API (#11). */
@RestController
public class ThumbnailController {

	private final ThumbnailService thumbnailService;

	ThumbnailController(ThumbnailService thumbnailService) {
		this.thumbnailService = thumbnailService;
	}

	/** 분석 한 건의 썸네일을 분석한다 (계정마다 아웃라이어·나머지 최대 15개씩). 키가 없으면 건너뛴다. */
	@PostMapping("/api/analyses/{analysisId}/thumbnails")
	TagResult tag(@PathVariable Long analysisId) {
		return thumbnailService.tag(analysisId);
	}

	/** 특징별 비율을 아웃라이어와 나머지로 비교한다. */
	@GetMapping("/api/analyses/{analysisId}/thumbnails")
	ThumbnailComparison compare(@PathVariable Long analysisId) {
		return thumbnailService.compare(analysisId);
	}
}
