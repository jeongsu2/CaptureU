package com.captureu.instagram.graph;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 응답 헤더의 호출 한도 사용량을 기록한다. 사용률이 높아지면 경고 로그를 남긴다.
 * 수집 속도 조절과 모니터링 지표(#17)는 이 값을 읽어서 쓴다.
 */
public class GraphApiUsageTracker {

	private static final Logger log = LoggerFactory.getLogger(GraphApiUsageTracker.class);
	static final String BUSINESS_USE_CASE_HEADER = "x-business-use-case-usage";
	static final String APP_USAGE_HEADER = "x-app-usage";
	private static final int WARN_PERCENT = 80;

	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final AtomicReference<GraphApiUsage> latest = new AtomicReference<>();

	public GraphApiUsageTracker(ObjectMapper objectMapper, Clock clock) {
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	public Optional<GraphApiUsage> latest() {
		return Optional.ofNullable(latest.get());
	}

	public void record(HttpHeaders headers) {
		parse(headers).ifPresent(usage -> {
			latest.set(usage);
			if (usage.maxPercent() >= WARN_PERCENT || usage.regainAccessMinutes() > 0) {
				log.warn("Graph API 호출 한도 사용률 {}% (다시 호출 가능까지 {}분)", usage.maxPercent(),
						usage.regainAccessMinutes());
			}
		});
	}

	/** 사용 사례 한도와 앱 한도 헤더를 모두 읽고, 더 높은 쪽을 쓴다 (앱 한도 초과는 x-app-usage에만 보인다). */
	Optional<GraphApiUsage> parse(HttpHeaders headers) {
		GraphApiUsage worst = null;
		String buc = headers.getFirst(BUSINESS_USE_CASE_HEADER);
		if (buc != null) {
			worst = higher(worst, readOrNull(buc, true));
		}
		String app = headers.getFirst(APP_USAGE_HEADER);
		if (app != null) {
			worst = higher(worst, readOrNull(app, false));
		}
		return Optional.ofNullable(worst);
	}

	private GraphApiUsage readOrNull(String header, boolean businessUseCase) {
		try {
			JsonNode root = objectMapper.readTree(header);
			return businessUseCase ? worstOf(root).orElse(null) : toUsage(root);
		}
		catch (RuntimeException e) {
			log.debug("호출 한도 헤더를 읽지 못함: {}", e.getMessage());
			return null;
		}
	}

	private static GraphApiUsage higher(GraphApiUsage a, GraphApiUsage b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		return b.maxPercent() > a.maxPercent() ? b : a;
	}

	/** {"<business id>": [{"type":"instagram", "call_count":1, ...}]} 에서 가장 높은 값을 고른다. */
	private Optional<GraphApiUsage> worstOf(JsonNode root) {
		GraphApiUsage worst = null;
		for (JsonNode entries : root.values()) {
			for (JsonNode entry : entries) {
				GraphApiUsage usage = toUsage(entry);
				if (worst == null || usage.maxPercent() > worst.maxPercent()) {
					worst = usage;
				}
			}
		}
		return Optional.ofNullable(worst);
	}

	private GraphApiUsage toUsage(JsonNode node) {
		return new GraphApiUsage(node.path("call_count").asInt(0), node.path("total_cputime").asInt(0),
				node.path("total_time").asInt(0), node.path("estimated_time_to_regain_access").asInt(0),
				Instant.now(clock));
	}
}
