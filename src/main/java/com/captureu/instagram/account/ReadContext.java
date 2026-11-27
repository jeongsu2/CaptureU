package com.captureu.instagram.account;

import java.util.HashSet;
import java.util.Set;

/**
 * 한 번의 분석 동안 쓰는 상태. 외부 API 호출 수와, 거부된 지표(다시 요청하지 않을 것)를 기억한다.
 * 한 분석 요청 안에서만 쓰고 공유하지 않는다.
 */
public class ReadContext {

	private int callCount;
	private final Set<String> unsupportedMetrics = new HashSet<>();

	void countCall() {
		callCount++;
	}

	public int callCount() {
		return callCount;
	}

	boolean isUnsupported(String productType, String metric) {
		return unsupportedMetrics.contains(productType + ":" + metric);
	}

	void markUnsupported(String productType, String metric) {
		unsupportedMetrics.add(productType + ":" + metric);
	}
}
