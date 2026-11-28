package com.captureu.domain.recommendation.exception;

public class InvalidTopicException extends RuntimeException {

	public InvalidTopicException() {
		super("주제를 한 줄로 넣어 주세요");
	}
}
