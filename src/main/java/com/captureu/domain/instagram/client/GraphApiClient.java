package com.captureu.domain.instagram.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import com.captureu.domain.instagram.config.GraphApiProperties;
import com.captureu.domain.instagram.exception.GraphApiErrorClassifier;
import com.captureu.domain.instagram.exception.GraphApiErrorType;
import com.captureu.domain.instagram.exception.GraphApiException;
import com.captureu.domain.instagram.util.TokenMasker;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 모든 Instagram Graph API 호출이 지나가는 클라이언트.
 * <ul>
 * <li>에러를 {@link GraphApiErrorType}으로 분류하고, 일시 장애만 지수 백오프로 바로 다시 시도한다.
 * 호출 한도 초과는 몇 분 이상 지속되므로 바로 재시도하지 않는다.</li>
 * <li>예외 메시지, 로그, 돌려주는 응답에서 access_token을 가린다.</li>
 * <li>응답 헤더의 호출 한도 사용량을 {@link GraphApiUsageTracker}에 기록한다.</li>
 * </ul>
 */
public class GraphApiClient {

	private static final Logger log = LoggerFactory.getLogger(GraphApiClient.class);

	private final RestClient restClient;
	private final GraphApiProperties properties;
	private final GraphApiUsageTracker usageTracker;
	private final ObjectMapper objectMapper;
	private final RetryTemplate retryTemplate;

	public GraphApiClient(RestClient.Builder restClientBuilder, GraphApiProperties properties,
			GraphApiUsageTracker usageTracker, ObjectMapper objectMapper) {
		this.restClient = restClientBuilder.build();
		this.properties = properties;
		this.usageTracker = usageTracker;
		this.objectMapper = objectMapper;
		this.retryTemplate = new RetryTemplate(RetryPolicy.builder()
			.maxRetries(properties.maxRetries())
			.delay(properties.retryDelay())
			.multiplier(2.0)
			.predicate(e -> e instanceof GraphApiException g && g.isRetryable())
			.build());
	}

	/**
	 * GET 호출. API 에러는 {@link GraphApiException}으로 던진다.
	 * 돌려주는 JSON의 paging.next/previous URL에 들어 있던 토큰은 가려져 있다 (다음 페이지는 커서로 요청할 것).
	 *
	 * @param path   버전 뒤 경로. 예: "17841400000000000/media"
	 * @param params 쿼리 파라미터 (access_token은 자동으로 붙는다)
	 */
	public JsonNode get(String path, Map<String, String> params) {
		if (!properties.hasAccessToken()) {
			throw new GraphApiException(GraphApiErrorType.TOKEN_INVALID, 0, null, null,
					"access token이 설정되지 않았습니다 (IG_ACCESS_TOKEN)");
		}
		URI uri = buildUri(path, params);
		int[] attempts = { 0 };
		try {
			return retryTemplate.execute(() -> {
				attempts[0]++;
				return callOnce(uri);
			});
		}
		catch (RetryException e) {
			throw unwrap(e, attempts[0]);
		}
	}

	private RuntimeException unwrap(RetryException e, int attempts) {
		Throwable last = e.getCause();
		if (Thread.currentThread().isInterrupted()) {
			return new IllegalStateException("Graph API 재시도 대기 중 스레드가 중단됨", last);
		}
		if (last instanceof Error error) {
			throw error;
		}
		if (last instanceof GraphApiException g) {
			return g.withAttempts(attempts);
		}
		// 프로그래밍 오류 등은 감추지 않고 원래 예외를 그대로 올린다.
		// (요청 URL이 들어간 RestClientException은 callOnce에서 이미 가린 예외로 바꿨다)
		return last instanceof RuntimeException r ? r : new IllegalStateException(last);
	}

	private JsonNode callOnce(URI uri) {
		try {
			return restClient.get().uri(uri).exchange((request, response) -> {
				usageTracker.record(response.getHeaders());
				int status = response.getStatusCode().value();
				String body = readBody(response.getBody());
				if (status >= 400) {
					throw toException(status, body);
				}
				JsonNode json = parseSuccessBody(status, body);
				if (json.has("error")) {
					throw toException(status, body);
				}
				maskPagingUrls(json);
				return json;
			});
		}
		catch (GraphApiException e) {
			log.debug("Graph API 실패: {}", e.getMessage());
			throw e;
		}
		catch (RestClientException e) {
			// 타임아웃, 연결 실패 등. 메시지에 요청 URL(토큰 포함)이 들어 있으므로 반드시 가린다.
			throw new GraphApiException(GraphApiErrorType.TEMPORARY, 0, null, null, mask(e.getMessage()));
		}
	}

	/** 2xx인데 본문이 비었거나 JSON이 아니면(게이트웨이 오류 페이지 등) 일시 장애로 본다. */
	private JsonNode parseSuccessBody(int status, String body) {
		if (body.isBlank()) {
			throw new GraphApiException(GraphApiErrorType.TEMPORARY, status, null, null, "빈 응답 본문");
		}
		try {
			return objectMapper.readTree(body);
		}
		catch (JacksonException e) {
			throw new GraphApiException(GraphApiErrorType.TEMPORARY, status, null, null, "JSON이 아닌 응답 본문");
		}
	}

	private GraphApiException toException(int status, String body) {
		Integer code = null;
		Integer subcode = null;
		boolean isTransient = false;
		String message = body;
		try {
			JsonNode error = objectMapper.readTree(body).path("error");
			if (!error.isMissingNode()) {
				code = error.hasNonNull("code") ? error.get("code").asInt() : null;
				subcode = error.hasNonNull("error_subcode") ? error.get("error_subcode").asInt() : null;
				isTransient = error.path("is_transient").asBoolean(false);
				message = error.path("message").asString("");
			}
		}
		catch (RuntimeException ignored) {
			// JSON이 아닌 응답(게이트웨이 오류 페이지 등)은 본문 앞부분만 남긴다
			message = body.length() > 200 ? body.substring(0, 200) : body;
		}
		GraphApiErrorType type = GraphApiErrorClassifier.classify(status, code, subcode, isTransient);
		return new GraphApiException(type, status, code, subcode, mask(message));
	}

	/** 응답 어디에 있든 paging.next/previous URL의 토큰을 가린다. 호출자가 응답을 로그에 남겨도 새지 않게 한다. */
	private void maskPagingUrls(JsonNode node) {
		if (node instanceof ObjectNode obj) {
			JsonNode paging = obj.get("paging");
			if (paging instanceof ObjectNode p) {
				for (String key : new String[] { "next", "previous" }) {
					if (p.hasNonNull(key)) {
						p.put(key, mask(p.get(key).asString()));
					}
				}
			}
		}
		for (JsonNode child : node.values()) {
			maskPagingUrls(child);
		}
	}

	/**
	 * 값은 직접 인코딩한다. 스프링의 쿼리 인코딩은 '+'를 그대로 두는데, 서버는 이를 공백으로 읽으므로
	 * (예: since=...+09:00) '+'만 따로 %2B로 바꾼다. fields 값의 {}는 인코딩되고 (), 쉼표는 그대로 둔다.
	 */
	private URI buildUri(String path, Map<String, String> params) {
		UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(properties.baseUrl())
			.pathSegment(properties.apiVersion())
			.path("/" + path);
		params.forEach((key, value) -> builder.queryParam(encode(key), encode(value)));
		builder.queryParam("access_token", encode(properties.accessToken()));
		return builder.build(true).toUri();
	}

	private static String encode(String value) {
		return UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8).replace("+", "%2B");
	}

	private String mask(String text) {
		return TokenMasker.mask(text, properties.accessToken());
	}

	private static String readBody(InputStream body) throws IOException {
		return body == null ? "" : new String(body.readAllBytes(), StandardCharsets.UTF_8);
	}
}
