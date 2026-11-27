package com.captureu.recommendation;

class InvalidTopicException extends RuntimeException {

	InvalidTopicException() {
		super("주제를 한 줄로 넣어 주세요");
	}
}
