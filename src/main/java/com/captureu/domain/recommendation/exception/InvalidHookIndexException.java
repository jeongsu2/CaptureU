package com.captureu.domain.recommendation.exception;

public class InvalidHookIndexException extends RuntimeException {

	public InvalidHookIndexException(int index, int size) {
		super("첫 줄 후보 번호는 0부터 %d까지예요. 받은 값: %d".formatted(size - 1, index));
	}
}
