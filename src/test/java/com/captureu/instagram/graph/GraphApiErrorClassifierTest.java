package com.captureu.instagram.graph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GraphApiErrorClassifierTest {

	@ParameterizedTest(name = "http={0}, code={1}, subcode={2} -> {3}")
	@CsvSource(nullValues = "null", value = {
			"403, 4,     null,    RATE_LIMITED",
			"403, 17,    null,    RATE_LIMITED",
			"403, 80002, null,    RATE_LIMITED",
			"429, null,  null,    RATE_LIMITED",
			"500, 1,     null,    TEMPORARY",
			"503, 2,     null,    TEMPORARY",
			"502, null,  null,    TEMPORARY",
			"400, 190,   464,     TOKEN_INVALID",
			"400, 190,   null,    TOKEN_INVALID",
			"400, 10,    null,    PERMISSION_DENIED",
			"400, 200,   null,    PERMISSION_DENIED",
			"400, 110,   2207013, TARGET_NOT_FOUND",
			"400, 100,   2207013, TARGET_NOT_FOUND",
			"400, 100,   33,      TARGET_NOT_FOUND",
			"400, 100,   null,    INVALID_REQUEST",
			"400, 9999,  null,    UNKNOWN" })
	void 에러_코드별_분류(int http, Integer code, Integer subcode, GraphApiErrorType expected) {
		assertThat(GraphApiErrorClassifier.classify(http, code, subcode)).isEqualTo(expected);
	}

	@org.junit.jupiter.api.Test
	void Meta가_일시적이라고_알려준_에러는_TEMPORARY다() {
		assertThat(GraphApiErrorClassifier.classify(400, 100, null, true)).isEqualTo(GraphApiErrorType.TEMPORARY);
		// 토큰 무효는 일시적이라고 해도 재시도하지 않는다
		assertThat(GraphApiErrorClassifier.classify(400, 190, null, true)).isEqualTo(GraphApiErrorType.TOKEN_INVALID);
	}

	@ParameterizedTest
	@CsvSource({ "RATE_LIMITED, false", "TEMPORARY, true", "TOKEN_INVALID, false", "TARGET_NOT_FOUND, false",
			"INVALID_REQUEST, false" })
	void 재시도_여부(GraphApiErrorType type, boolean retryable) {
		assertThat(type.isRetryable()).isEqualTo(retryable);
	}
}
