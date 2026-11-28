package com.captureu.domain.auth.client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import com.captureu.domain.auth.config.AuthProperties;
import com.captureu.domain.instagram.config.GraphApiProperties;
import com.captureu.domain.instagram.util.TokenMasker;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Meta 로그인 호출 (#16): 로그인 창 주소, 코드 → 토큰 교환, 장기 토큰 교환, 토큰에 연결된 인스타 계정 찾기.
 * 앱 시크릿과 토큰이 쿼리에 들어가므로 요청 주소와 응답 원문은 로그·예외에 남기지 않는다.
 */
@Component
public class MetaLoginClient {

	private static final String DIALOG_HOST = "https://www.facebook.com";

	private final RestClient restClient;
	private final AuthProperties auth;
	private final GraphApiProperties graph;
	private final JsonMapper json = JsonMapper.builder().build();

	public MetaLoginClient(RestClient.Builder builder, AuthProperties auth, GraphApiProperties graph) {
		this.restClient = builder.build();
		this.auth = auth;
		this.graph = graph;
	}

	/**
	 * 비즈니스용 Facebook 로그인 창 주소. 권한은 구성(config_id)에 들어 있어 scope를 넣지 않는다.
	 * 시스템 사용자 토큰 구성은 코드 방식만 지원해서 response_type=code로 요청한다.
	 */
	public String authorizeUrl(String state) {
		return UriComponentsBuilder.fromUriString(DIALOG_HOST)
			.pathSegment(graph.apiVersion(), "dialog", "oauth")
			.queryParam("client_id", encode(auth.appId()))
			.queryParam("redirect_uri", encode(auth.redirectUri()))
			.queryParam("state", encode(state))
			.queryParam("config_id", encode(auth.configId()))
			.queryParam("response_type", "code")
			.queryParam("override_default_response_type", "true")
			.build(true)
			.toUriString();
	}

	/** @param expiresIn 초. null이면 만료 없음 */
	public record TokenResponse(String accessToken, Long expiresIn) {

		@Override
		public String toString() {
			return "TokenResponse[accessToken=***, expiresIn=%s]".formatted(expiresIn);
		}
	}

	public record InstagramAccount(String igUserId, String username) {
	}

	public TokenResponse exchangeCode(String code) {
		JsonNode body = get("oauth/access_token", Map.of("client_id", auth.appId(), "redirect_uri",
				auth.redirectUri(), "client_secret", auth.appSecret(), "code", code), null);
		return token(body);
	}

	/** 단기 사용자 토큰(약 1~2시간)을 약 60일짜리로 바꾼다. */
	public TokenResponse exchangeLongLived(String shortLived) {
		JsonNode body = get("oauth/access_token", Map.of("grant_type", "fb_exchange_token", "client_id",
				auth.appId(), "client_secret", auth.appSecret(), "fb_exchange_token", shortLived), shortLived);
		return token(body);
	}

	/**
	 * 토큰에 연결된 인스타 프로페셔널 계정.
	 * Unified Login 토큰(비즈니스 연동 시스템 사용자)은 페이지 없이 계정이 바로 배정되므로 me/assigned_instagram_accounts로 찾는다.
	 * 거기서 못 찾으면(페이스북 페이지에 연결한 계정) 페이지 → instagram_business_account로 찾는다.
	 */
	public Optional<InstagramAccount> findInstagramAccount(String accessToken) {
		MetaLoginException assignedError = null;
		try {
			JsonNode assigned = get("me/assigned_instagram_accounts", Map.of("fields", "id,username"), accessToken);
			for (JsonNode ig : assigned.path("data")) {
				String id = ig.path("id").asString(null);
				if (id != null) {
					return Optional.of(new InstagramAccount(id, ig.path("username").asString(null)));
				}
			}
		}
		catch (MetaLoginException e) {
			// 시스템 사용자 토큰이 아니면 이 경로가 없을 수 있다. 페이지 방식으로 한 번 더 찾는다
			assignedError = e;
		}
		try {
			return findThroughPages(accessToken);
		}
		catch (MetaLoginException e) {
			throw assignedError != null ? assignedError : e;
		}
	}

	private Optional<InstagramAccount> findThroughPages(String accessToken) {
		JsonNode pages = get("me/accounts", Map.of("fields", "instagram_business_account{id,username}"), accessToken);
		for (JsonNode page : pages.path("data")) {
			JsonNode ig = page.path("instagram_business_account");
			String id = ig.path("id").asString(null);
			if (id != null) {
				return Optional.of(new InstagramAccount(id, ig.path("username").asString(null)));
			}
		}
		return Optional.empty();
	}

	private static TokenResponse token(JsonNode body) {
		String token = body.path("access_token").asString(null);
		if (token == null || token.isBlank()) {
			throw new MetaLoginException("토큰 응답에 access_token이 없습니다");
		}
		JsonNode expires = body.path("expires_in");
		return new TokenResponse(token, expires.isNumber() ? expires.asLong() : null);
	}

	private JsonNode get(String path, Map<String, String> params, String accessToken) {
		UriComponentsBuilder b = UriComponentsBuilder.fromUriString(graph.baseUrl())
			.pathSegment(graph.apiVersion())
			.path("/" + path);
		params.forEach((k, v) -> b.queryParam(encode(k), encode(v)));
		if (accessToken != null) {
			b.queryParam("access_token", encode(accessToken));
		}
		URI uri = b.build(true).toUri();
		try {
			return restClient.get().uri(uri).exchange((request, response) -> {
				String text = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
				JsonNode node = text.isBlank() ? json.createObjectNode() : json.readTree(text);
				if (response.getStatusCode().isError() || node.has("error")) {
					String message = node.path("error").path("message").asString("HTTP " + response.getStatusCode().value());
					throw new MetaLoginException(mask(message, accessToken));
				}
				return node;
			});
		}
		catch (RestClientException e) {
			// 예외 메시지에 요청 주소(시크릿·토큰 포함)가 들어갈 수 있어 원문을 버린다
			throw new MetaLoginException("Meta 호출 실패: " + e.getClass().getSimpleName());
		}
	}

	private String mask(String text, String accessToken) {
		String masked = TokenMasker.mask(text, accessToken);
		return auth.appSecret() == null ? masked : masked.replace(auth.appSecret(), "***");
	}

	private static String encode(String value) {
		return UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8).replace("+", "%2B");
	}

	public static class MetaLoginException extends RuntimeException {

		public MetaLoginException(String message) {
			super(message);
		}
	}
}
