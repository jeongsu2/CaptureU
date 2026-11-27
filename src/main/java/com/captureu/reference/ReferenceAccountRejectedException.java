package com.captureu.reference;

import java.util.List;

import com.captureu.reference.ReferenceAccountService.ReferenceRejection;

/** 등록하려는 참고 계정 중 조회할 수 없는 계정이 있음. */
public class ReferenceAccountRejectedException extends RuntimeException {

	private final List<ReferenceRejection> rejections;

	public ReferenceAccountRejectedException(List<ReferenceRejection> rejections) {
		super("참고 계정을 등록할 수 없습니다");
		this.rejections = List.copyOf(rejections);
	}

	public List<ReferenceRejection> getRejections() {
		return rejections;
	}
}
