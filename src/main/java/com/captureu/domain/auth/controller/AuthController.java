package com.captureu.domain.auth.controller;

import java.security.SecureRandom;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.captureu.domain.auth.client.MetaLoginClient.MetaLoginException;
import com.captureu.domain.auth.dto.response.MeResponse;
import com.captureu.domain.auth.entity.AppUser;
import com.captureu.domain.auth.service.AuthService;
import com.captureu.domain.auth.service.AuthService.NoInstagramAccountException;
import com.captureu.domain.auth.service.CurrentAccountResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/** 인스타 로그인 (#16). 로그인 창으로 보내고, 돌아오면 세션에 계정을 넣고 첫 화면으로 보낸다. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private static final Logger log = LoggerFactory.getLogger(AuthController.class);
	private static final String STATE_KEY = "captureu.oauthState";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final AuthService authService;
	private final CurrentAccountResolver resolver;

	public AuthController(AuthService authService, CurrentAccountResolver resolver) {
		this.authService = authService;
		this.resolver = resolver;
	}

	@GetMapping("/login")
	public ResponseEntity<Void> login(HttpSession session) {
		if (!authService.loginAvailable()) {
			return redirect("/?login=unavailable");
		}
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String state = HexFormat.of().formatHex(bytes);
		// 다른 사이트가 자기 코드로 콜백을 부르게 하는 공격(CSRF)을 막으려고 state를 세션과 맞춰 본다
		session.setAttribute(STATE_KEY, state);
		return redirect(authService.authorizeUrl(state));
	}

	@GetMapping("/callback")
	public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
			@RequestParam(required = false) String state, @RequestParam(required = false) String error,
			HttpServletRequest request) {
		HttpSession session = request.getSession();
		Object expected = session.getAttribute(STATE_KEY);
		session.removeAttribute(STATE_KEY);
		if (error != null) {
			return redirect("/?login=cancelled");
		}
		if (code == null || state == null || !state.equals(expected)) {
			return redirect("/?login=invalid");
		}
		try {
			AppUser user = authService.login(code);
			// 로그인 전 세션 ID를 그대로 쓰지 않는다 (세션 고정 공격 방지)
			request.changeSessionId();
			session.setAttribute(CurrentAccountResolver.SESSION_KEY, user.getIgUserId());
			return redirect("/?login=ok");
		}
		catch (NoInstagramAccountException e) {
			return redirect("/?login=no-instagram");
		}
		catch (MetaLoginException e) {
			log.warn("인스타 로그인 실패: {}", e.getMessage());
			return redirect("/?login=failed");
		}
		catch (RuntimeException e) {
			// 응답 형식 오류 등. 원문에 토큰이 섞일 수 있어 종류만 남긴다
			log.warn("인스타 로그인 중 예상하지 못한 오류: {}", e.getClass().getSimpleName());
			return redirect("/?login=failed");
		}
	}

	@GetMapping("/me")
	public MeResponse me() {
		return resolver.find()
			.map(a -> new MeResponse(true, a.igUserId(), a.username(), a.source().name(),
					authService.loginAvailable()))
			.orElseGet(() -> new MeResponse(false, null, null, null, authService.loginAvailable()));
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		return ResponseEntity.noContent().build();
	}

	private static ResponseEntity<Void> redirect(String location) {
		return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
	}
}
