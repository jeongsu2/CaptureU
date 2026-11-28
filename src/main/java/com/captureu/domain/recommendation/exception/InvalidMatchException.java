package com.captureu.domain.recommendation.exception;

/** 연결하려는 게시물이 이 분석의 내 게시물이 아님. 400. */
public class InvalidMatchException extends RuntimeException {

	public InvalidMatchException(String message) {
		super(message);
	}
}
