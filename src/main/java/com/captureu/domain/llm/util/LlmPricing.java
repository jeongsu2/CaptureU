package com.captureu.domain.llm.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** Claude 모델별 백만 토큰당 달러 (입력, 출력). 응답의 model로 비용을 계산한다. */
public final class LlmPricing {

	private static final Map<String, double[]> PRICES = Map.of(
			"claude-opus-5-5", new double[] { 4, 20 },
			"claude-opus-5", new double[] { 5, 25 },
			"claude-opus-4-8", new double[] { 5, 25 },
			"claude-sonnet-5-5", new double[] { 2, 10 },
			"claude-haiku-5-5", new double[] { 0.1, 0.5 });

	private LlmPricing() {
	}

	/** 가격을 모르는 모델이면 null. */
	public static BigDecimal cost(String model, long inputTokens, long outputTokens) {
		double[] price = PRICES.get(model);
		if (price == null) {
			return null;
		}
		double usd = (inputTokens * price[0] + outputTokens * price[1]) / 1_000_000.0;
		return BigDecimal.valueOf(usd).setScale(6, RoundingMode.HALF_UP);
	}
}
