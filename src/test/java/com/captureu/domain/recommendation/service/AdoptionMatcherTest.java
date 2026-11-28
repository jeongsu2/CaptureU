package com.captureu.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdoptionMatcherTest {

	@Test
	void 그대로_쓰고_이모지나_해시태그를_덧붙여도_같은_문구로_본다() {
		assertThat(AdoptionMatcher.similarity("겨울 출근룩, 아직도 이렇게 하세요?", "겨울 출근룩, 아직도 이렇게 하세요? 🧣 #출근룩"))
			.isEqualTo(1.0);
	}

	@Test
	void 조금_고쳐_쓰면_자동_연결_기준을_넘는다() {
		assertThat(AdoptionMatcher.similarity("겨울 출근룩, 아직도 이렇게 하세요?", "겨울 출근룩 아직도 이렇게 입으세요??"))
			.isGreaterThanOrEqualTo(AdoptionMatcher.AUTO);
	}

	@Test
	void 일부만_겹치면_사용자에게_묻는_구간이다() {
		assertThat(AdoptionMatcher.similarity("겨울 출근룩 3가지 모음.zip", "출근룩 3가지 정리"))
			.isGreaterThanOrEqualTo(AdoptionMatcher.ASK)
			.isLessThan(AdoptionMatcher.AUTO);
	}

	@Test
	void 다른_내용이면_연결하지_않는다() {
		assertThat(AdoptionMatcher.similarity("겨울 출근룩, 아직도 이렇게 하세요?", "주말 브이로그 카페 투어"))
			.isLessThan(AdoptionMatcher.ASK);
		assertThat(AdoptionMatcher.similarity("겨울 출근룩", null)).isZero();
		assertThat(AdoptionMatcher.similarity("겨울 출근룩", "🧣🧣")).isZero();
	}

	@Test
	void 긴_문구에_짧은_조각이_들어_있는_것만으로는_같다고_보지_않는다() {
		assertThat(AdoptionMatcher.similarity("직장인이라면 꼭 알아야 할 3가지", "직장인이라면")).isLessThan(AdoptionMatcher.AUTO);
	}
}
