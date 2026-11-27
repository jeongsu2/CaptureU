package com.captureu.common;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 요청 본문 검증 실패를 화면에 보여 줄 수 있는 문장(detail)으로 돌려준다. */
@RestControllerAdvice
class RequestValidationHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail invalid(MethodArgumentNotValidException e) {
		String detail = e.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(f -> f.getDefaultMessage())
			.distinct()
			.collect(Collectors.joining(" "));
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				detail.isBlank() ? "입력값을 확인해 주세요" : detail);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail unreadable(HttpMessageNotReadableException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않아요");
	}
}
