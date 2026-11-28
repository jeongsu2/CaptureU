package com.captureu.domain.auth.dto;

/**
 * 이번 요청이 쓰는 인스타 계정 (#16).
 *
 * @param accessToken 로그인 사용자의 토큰. .env 계정이면 null (클라이언트가 IG_ACCESS_TOKEN을 쓴다)
 * @param source      LOGIN(인스타 로그인) 또는 ENV(.env 개발용 계정)
 */
public record CurrentAccount(String igUserId, String username, String accessToken, Source source) {

	public enum Source {
		LOGIN, ENV
	}

	@Override
	public String toString() {
		return "CurrentAccount[igUserId=%s, username=%s, source=%s]".formatted(igUserId, username, source);
	}
}
