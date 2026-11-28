package com.captureu.domain.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.captureu.domain.auth.client.MetaLoginClient;
import com.captureu.domain.auth.client.MetaLoginClient.InstagramAccount;
import com.captureu.domain.auth.client.MetaLoginClient.MetaLoginException;
import com.captureu.domain.auth.client.MetaLoginClient.TokenResponse;
import com.captureu.domain.auth.config.AuthProperties;
import com.captureu.domain.auth.entity.AppUser;
import com.captureu.domain.auth.repository.AppUserRepository;

/** 인스타 로그인 (#16): 코드 → 토큰 → 인스타 계정 → 암호화 저장. */
@Service
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);
	/** 이보다 짧게 남은 토큰은 단기 사용자 토큰으로 보고 장기 토큰으로 바꾼다. */
	private static final Duration SHORT_LIVED = Duration.ofDays(1);

	private final MetaLoginClient metaClient;
	private final AppUserRepository userRepository;
	private final CurrentAccountResolver resolver;
	private final AuthProperties auth;
	private final Clock clock;

	public AuthService(MetaLoginClient metaClient, AppUserRepository userRepository, CurrentAccountResolver resolver,
			AuthProperties auth, Clock clock) {
		this.metaClient = metaClient;
		this.userRepository = userRepository;
		this.resolver = resolver;
		this.auth = auth;
		this.clock = clock;
	}

	public boolean loginAvailable() {
		return auth.loginAvailable();
	}

	public String authorizeUrl(String state) {
		return metaClient.authorizeUrl(state);
	}

	/** 로그인 성공 시 사용자. 인스타 프로페셔널 계정을 찾지 못하면 {@link NoInstagramAccountException}. */
	public AppUser login(String code) {
		TokenResponse token = metaClient.exchangeCode(code);
		if (token.expiresIn() != null && token.expiresIn() < SHORT_LIVED.toSeconds()) {
			try {
				token = metaClient.exchangeLongLived(token.accessToken());
			}
			catch (MetaLoginException e) {
				// 시스템 사용자 토큰처럼 바꿀 수 없는 토큰이면 받은 토큰을 그대로 쓴다
				log.info("장기 토큰으로 바꾸지 못해 받은 토큰을 씀: {}", e.getMessage());
			}
		}
		InstagramAccount account = metaClient.findInstagramAccount(token.accessToken())
			.orElseThrow(NoInstagramAccountException::new);
		Instant now = clock.instant();
		Instant expiresAt = token.expiresIn() == null ? null : now.plusSeconds(token.expiresIn());
		String encrypted = resolver.cipher().encrypt(token.accessToken());
		AppUser user = userRepository.findById(account.igUserId()).map(u -> {
			u.refresh(account.username(), encrypted, expiresAt, now);
			return u;
		}).orElseGet(() -> AppUser.create(account.igUserId(), account.username(), encrypted, expiresAt, now));
		AppUser saved = userRepository.save(user);
		log.info("인스타 로그인 igUserId={} 만료={}", saved.getIgUserId(), expiresAt == null ? "없음" : expiresAt);
		return saved;
	}

	public static class NoInstagramAccountException extends RuntimeException {

		public NoInstagramAccountException() {
			super("로그인한 계정에 연결된 인스타 프로페셔널 계정을 찾지 못했어요");
		}
	}
}
