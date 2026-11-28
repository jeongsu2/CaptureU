package com.captureu.domain.recommendation.exception;

/** 다른 채택과 부딪힘 (같은 게시물을 두 채택에 연결하려 함). 409. */
public class AdoptionConflictException extends RuntimeException {

	public AdoptionConflictException(String message) {
		super(message);
	}
}
