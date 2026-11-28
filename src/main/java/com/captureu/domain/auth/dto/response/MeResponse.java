package com.captureu.domain.auth.dto.response;

/**
 * @param connected      쓸 수 있는 인스타 계정이 있는지
 * @param source         LOGIN 또는 ENV. 연결된 계정이 없으면 null
 * @param loginAvailable 서버에 Meta 앱 설정이 있어 인스타 로그인을 쓸 수 있는지
 */
public record MeResponse(boolean connected, String igUserId, String username, String source,
		boolean loginAvailable) {
}
