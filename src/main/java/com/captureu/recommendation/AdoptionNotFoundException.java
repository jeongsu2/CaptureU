package com.captureu.recommendation;

public class AdoptionNotFoundException extends RuntimeException {

	public AdoptionNotFoundException(Long id) {
		super("채택 기록을 찾을 수 없습니다. id=" + id);
	}
}
