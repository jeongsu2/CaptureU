package com.captureu.reference;

/** 같은 사용자의 참고 계정 변경이 이미 진행 중. */
public class ReferenceUpdateInProgressException extends RuntimeException {

	public ReferenceUpdateInProgressException() {
		super("참고 계정을 바꾸는 중이에요. 끝난 뒤 다시 시도해 주세요");
	}
}
