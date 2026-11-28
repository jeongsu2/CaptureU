package com.captureu.domain.profile.entity;

/** 크리에이터 컨셉 (#15). 추천 프롬프트에 한글 이름으로 넣는다. */
public enum Concept {

	BEAUTY("뷰티"), FASHION("패션"), FOOD("요리·맛집"), TRAVEL("여행"), PARENTING("육아"), FITNESS("운동"),
	DAILY("일상·브이로그"), PET("반려동물"), INTERIOR("인테리어"), TECH("테크"), EDUCATION("공부·정보"),
	COUPLE("커플"), OTHER("기타");

	private final String label;

	Concept(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
