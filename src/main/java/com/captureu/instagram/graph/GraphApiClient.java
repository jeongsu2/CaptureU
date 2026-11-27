package com.captureu.instagram.graph;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 모든 Instagram Graph API 호출이 지나가는 클라이언트.
 * <ul>
 * <li>에러를 {@link GraphApiErrorType}으로 분류하고, 재시도해도 되는 에러만 지수 백오프로 다시 시도한다.</li>
 * <li>예외 메시지와 로그에서 access_token을 가린다.</li>
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
	 * GET 호출. 실패하면 {@link GraphApiException}을 던진다.
	 *
	 * @param path   버전 뒤 경로. 예: "{ig-user-id}/media"
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
			Throwable last = e.getLastException() != null ? e.getLastException() : e.getCause();
			if (last instanceof GraphApiException g) {
				throw g.withAttempts(attempts[0]);
			}
			throw new GraphApiException(GraphApiErrorType.UNKNOWN, 0, null, null, mask(String.valueOf(last)))
				.withAttempts(attempts[0]);
		}
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
				JsonNode json = objectMapper.readTree(body);
				if (json.has("error")) {
					throw toException(status, body);
				}
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

	private GraphApiException toException(int status, String body) {
		Integer code = null;
		Integer subcode = null;
		String message = body;
		try {
			JsonNode error = objectMapper.readTree(body).path("error");
			if (!error.isMissingNode()) {
				code = error.hasNonNull("code") ? error.get("code").asInt() : null;
				subcode = error.hasNonNull("error_subcode") ? error.get("error_subcode").asInt() : null;
				message = error.path("message").asString("");
			}
		}
		catch (RuntimeException ignored) {
			// JSON이 아닌 응답(게이트웨이 오류 페이지 등)은 본문 앞부분만 남긴다
			message = body.length() > 200 ? body.substring(0, 200) : body;
		}
		GraphApiErrorType type = GraphApiErrorClassifier.classify(status, code, subcode);
		return new GraphApiException(type, status, code, subcode, mask(message));
	}

	private URI buildUri(String path, Map<String, String> params) {
		UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(properties.baseUrl())
			.pathSegment(properties.apiVersion())
			.path("/" + path);
		params.forEach(builder::queryParam);
		builder.queryParam("access_token", properties.accessToken());
		// fields 값에 {}가 들어가므로(예: media.limit(50){caption}) 템플릿으로 해석하지 않고 그대로 인코딩한다
		return builder.build().encode().toUri();
	}

	private String mask(String text) {
		return TokenMasker.mask(text, properties.accessToken());
	}

	private static String readBody(InputStream body) throws IOException {
		return body == null ? "" : new String(body.readAllBytes(), StandardCharsets.UTF_8);
	}
}
