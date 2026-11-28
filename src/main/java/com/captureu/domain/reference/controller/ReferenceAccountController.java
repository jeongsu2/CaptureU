package com.captureu.domain.reference.controller;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.captureu.domain.analysis.exception.AccountNotConnectedException;
import com.captureu.domain.auth.dto.CurrentAccount;
import com.captureu.domain.auth.service.CurrentAccountResolver;
import com.captureu.domain.reference.dto.request.ReplaceRequest;
import com.captureu.domain.reference.dto.response.ReferenceAccountResponse;
import com.captureu.domain.reference.exception.ReferenceAccountRejectedException;
import com.captureu.domain.reference.exception.ReferenceUpdateInProgressException;
import com.captureu.domain.reference.service.ReferenceAccountService;

import jakarta.validation.Valid;

/** 참고 계정 등록·조회. 지금은 .env에 설정한 내 계정 하나 기준 (사용자 로그인은 #16). */
@RestController
@RequestMapping("/api/reference-accounts")
public class ReferenceAccountController {

	private final ReferenceAccountService service;
	private final CurrentAccountResolver accounts;

	ReferenceAccountController(ReferenceAccountService service, CurrentAccountResolver accounts) {
		this.service = service;
		this.accounts = accounts;
	}

	@GetMapping
	List<ReferenceAccountResponse> list() {
		return service.list(accounts.current().igUserId()).stream().map(ReferenceAccountResponse::of).toList();
	}

	/** 예: {"usernames": ["account_a", "account_b"]}. 기존 목록을 통째로 바꾼다. */
	@PutMapping
	List<ReferenceAccountResponse> replace(@Valid @RequestBody ReplaceRequest request) {
		CurrentAccount account = accounts.current();
		return service.replace(account.igUserId(), account.accessToken(), request.usernames())
			.stream().map(ReferenceAccountResponse::of).toList();
	}

	@ExceptionHandler(ReferenceAccountRejectedException.class)
	ProblemDetail rejected(ReferenceAccountRejectedException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
		problem.setProperty("rejections", e.getRejections());
		return problem;
	}

	@ExceptionHandler(AccountNotConnectedException.class)
	ProblemDetail notConnected(AccountNotConnectedException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
	}

	@ExceptionHandler({ ReferenceUpdateInProgressException.class, DataIntegrityViolationException.class })
	ProblemDetail conflict(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "참고 계정을 바꾸는 중이에요. 끝난 뒤 다시 시도해 주세요");
	}
}
