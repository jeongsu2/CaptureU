package com.captureu.recommendation;

/** 연결하려는 게시물이 이 분석의 내 게시물이 아님. 400. */
class InvalidMatchException extends RuntimeException {

	InvalidMatchException(String message) {
		super(message);
	}
}
