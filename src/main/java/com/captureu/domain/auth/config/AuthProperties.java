package com.captureu.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 인스타 로그인(Meta 비즈니스용 Facebook 로그인, Unified Login) 설정 (#16). 값은 로컬 .env에서 읽는다.
 *
 * @param appId              Meta 앱 ID
 * @param appSecret          Meta 앱 시크릿. 코드 교환에만 쓰고 로그·응답에 남기지 않는다
 * @param configId           비즈니스용 Facebook 로그인 구성 ID (권한 목록은 구성에 들어 있다)
 * @param redirectUri        Meta 대시보드의 유효한 OAuth 리디렉션 URI에 등록한 주소와 정확히 같아야 한다
 * @param tokenEncryptionKey 사용자 토큰 암호화 키 (Base64, 32바이트)
 * @param envFallback        로그인하지 않았을 때 .env의 IG_USER_ID·IG_ACCESS_TOKEN 계정을 쓸지. 로컬 개발 프로필에서만 켠다
 */
@ConfigurationProperties("captureu.auth")
public record AuthProperties(String appId, String appSecret, String configId,
		@DefaultValue("http://localhost:8080/api/auth/callback") String redirectUri, String tokenEncryptionKey,
		@DefaultValue("false") boolean envFallback) {

	public boolean loginAvailable() {
		return notBlank(appId) && notBlank(appSecret) && notBlank(configId) && notBlank(tokenEncryptionKey);
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}

	@Override
	public String toString() {
		return "AuthProperties[appId=%s, appSecret=%s, configId=%s, redirectUri=%s, tokenEncryptionKey=%s, envFallback=%s]"
			.formatted(appId, notBlank(appSecret) ? "***" : "(없음)", configId, redirectUri,
					notBlank(tokenEncryptionKey) ? "***" : "(없음)", envFallback);
	}
}
