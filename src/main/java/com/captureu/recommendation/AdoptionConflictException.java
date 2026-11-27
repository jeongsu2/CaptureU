package com.captureu.recommendation;

/** 다른 채택과 부딪힘 (같은 게시물을 두 채택에 연결하려 함). 409. */
class AdoptionConflictException extends RuntimeException {

	AdoptionConflictException(String message) {
		super(message);
	}
}
