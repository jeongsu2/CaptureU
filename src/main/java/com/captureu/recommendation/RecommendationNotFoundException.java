package com.captureu.recommendation;

public class RecommendationNotFoundException extends RuntimeException {

	public RecommendationNotFoundException(Long id) {
		super("추천을 찾을 수 없습니다. id=" + id);
	}
}
