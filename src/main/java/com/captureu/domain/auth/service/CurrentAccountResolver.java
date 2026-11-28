package com.captureu.domain.auth.service;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import com.captureu.domain.analysis.exception.AccountNotConnectedException;
import com.captureu.domain.auth.config.AuthProperties;
import com.captureu.domain.auth.dto.CurrentAccount;
import com.captureu.domain.auth.dto.CurrentAccount.Source;
import com.captureu.domain.auth.entity.AppUser;
import com.captureu.domain.auth.repository.AppUserRepository;
import com.captureu.domain.auth.util.TokenCipher;
import com.captureu.domain.instagram.config.GraphApiProperties;

/**
 * 요청마다 어떤 인스타 계정으로 일할지 정한다 (#16).
 * 세션에 로그인한 사용자가 있으면 그 계정과 토큰, 없으면(로컬 개발) .env의 계정을 쓴다.
 */
@Component
public class CurrentAccountResolver {

	public static final String SESSION_KEY = "captureu.igUserId";

	private final AppUserRepository userRepository;
	private final AuthProperties auth;
	private final GraphApiProperties graph;
	private volatile TokenCipher cipher;

	public CurrentAccountResolver(AppUserRepository userRepository, AuthProperties auth, GraphApiProperties graph) {
		this.userRepository = userRepository;
		this.auth = auth;
		this.graph = graph;
	}

	/** 쓸 수 있는 계정이 없으면 {@link AccountNotConnectedException}. */
	public CurrentAccount current() {
		return find().orElseThrow(AccountNotConnectedException::new);
	}

	public Optional<CurrentAccount> find() {
		Object loggedIn = sessionAttribute();
		if (loggedIn instanceof String igUserId) {
			Optional<CurrentAccount> user = forUser(igUserId);
			if (user.isPresent()) {
				return user;
			}
		}
		return envAccount();
	}

	/** 스케줄러처럼 요청 밖에서 특정 계정으로 일할 때. 로그인 사용자 → .env 계정 순으로 찾는다. */
	public Optional<CurrentAccount> forOwner(String igUserId) {
		return forUser(igUserId).or(() -> envAccount().filter(a -> a.igUserId().equals(igUserId)));
	}

	private Optional<CurrentAccount> forUser(String igUserId) {
		if (!auth.loginAvailable()) {
			return Optional.empty();
		}
		return userRepository.findById(igUserId).map(this::toAccount);
	}

	private CurrentAccount toAccount(AppUser user) {
		return new CurrentAccount(user.getIgUserId(), user.getUsername(), cipher().decrypt(user.getAccessTokenEnc()),
				Source.LOGIN);
	}

	private Optional<CurrentAccount> envAccount() {
		String id = graph.igUserId();
		if (!auth.envFallback() || id == null || id.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(new CurrentAccount(id, null, null, Source.ENV));
	}

	TokenCipher cipher() {
		TokenCipher c = cipher;
		if (c == null) {
			c = new TokenCipher(auth.tokenEncryptionKey());
			cipher = c;
		}
		return c;
	}

	private static Object sessionAttribute() {
		RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
		return attrs == null ? null : attrs.getAttribute(SESSION_KEY, RequestAttributes.SCOPE_SESSION);
	}
}
