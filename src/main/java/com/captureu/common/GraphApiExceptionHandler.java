package com.captureu.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.captureu.instagram.graph.GraphApiException;

/**
 * 요청 처리 중 인스타 API 호출이 실패했을 때의 응답. 사용자 입력 문제가 아니므로 400을 쓰지 않는다.
 * 응답에는 고정된 안내 문구만 넣는다 (외부 에러 메시지를 그대로 내보내지 않음).
 */
@RestControllerAdvice
class GraphApiExceptionHandler {

	@ExceptionHandler(GraphApiException.class)
	ProblemDetail handle(GraphApiException e) {
		ProblemDetail problem = switch (e.getType()) {
			case RATE_LIMITED -> ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
					"인스타그램 호출 한도에 걸렸어요. 잠시 후 다시 시도해 주세요");
			case TOKEN_INVALID -> ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
					"인스타그램 계정 연결이 끊겼어요. 다시 연결해 주세요");
			default -> ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
					"인스타그램에서 정보를 받지 못했어요. 잠시 후 다시 시도해 주세요");
		};
		problem.setProperty("errorType", e.getType().name());
		return problem;
	}
}
