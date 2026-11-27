package com.captureu.instagram.account;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.captureu.instagram.graph.GraphApiClient;
import com.captureu.instagram.graph.GraphApiErrorType;
import com.captureu.instagram.graph.GraphApiException;

import tools.jackson.databind.JsonNode;

/**
 * 연결된 내 인스타 계정의 정보, 게시물, 인사이트, 연령대를 Graph API로 읽는다.
 * 저장은 하지 않는다. 호출 수는 {@link ReadContext}에 센다.
 */
@Component
public class InstagramAccountReader {

	private static final Logger log = LoggerFactory.getLogger(InstagramAccountReader.class);

	static final int PAGE_SIZE = 50;
	/** 무한 루프 방지. 50개씩 100페이지 = 5,000개. */
	static final int MAX_PAGES = 100;

	static final List<String> REELS_METRICS = List.of("views", "reach", "saved", "shares", "total_interactions",
			"ig_reels_avg_watch_time", "reels_skip_rate");
	static final List<String> FEED_METRICS = List.of("views", "reach", "saved", "shares", "total_interactions");

	private static final String MEDIA_FIELDS = "id,media_type,media_product_type,timestamp,caption,thumbnail_url,"
			+ "media_url,permalink,like_count,comments_count";
	private static final DateTimeFormatter GRAPH_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

	/** 이 에러면 계정 전체 수집을 멈춘다. 그 외는 해당 게시물·지표만 건너뛴다. */
	private static final Set<GraphApiErrorType> FATAL = Set.of(GraphApiErrorType.TOKEN_INVALID,
			GraphApiErrorType.RATE_LIMITED);

	private final GraphApiClient client;

	public InstagramAccountReader(GraphApiClient client) {
		this.client = client;
	}

	public AccountInfo readAccount(String igUserId, ReadContext ctx) {
		JsonNode node = get(ctx, igUserId, Map.of("fields", "id,username,followers_count,media_count"));
		return new AccountInfo(node.path("id").asString(igUserId), textOrNull(node, "username"),
				intOrNull(node, "followers_count"), intOrNull(node, "media_count"));
	}

	/** 게시물 전체를 커서가 끝날 때까지 받는다. media_count는 실제 개수와 다를 수 있어 쓰지 않는다. */
	public List<MediaItem> readAllMedia(String igUserId, ReadContext ctx) {
		List<MediaItem> items = new ArrayList<>();
		String after = null;
		for (int page = 0; page < MAX_PAGES; page++) {
			Map<String, String> params = new LinkedHashMap<>();
			params.put("fields", MEDIA_FIELDS);
			params.put("limit", String.valueOf(PAGE_SIZE));
			if (after != null) {
				params.put("after", after);
			}
			JsonNode node = get(ctx, igUserId + "/media", params);
			JsonNode data = node.path("data");
			for (JsonNode m : data) {
				items.add(toMediaItem(m));
			}
			JsonNode paging = node.path("paging");
			after = paging.path("cursors").path("after").asString(null);
			// paging.next가 없으면 마지막 페이지. next 값(토큰이 든 URL)은 쓰지 않고 커서만 쓴다.
			if (!paging.has("next") || data.isEmpty() || after == null) {
				return items;
			}
		}
		log.warn("게시물 페이지가 {}쪽을 넘어 중단함 (받은 게시물 {}개)", MAX_PAGES, items.size());
		return items;
	}

	/**
	 * 게시물 인사이트. 릴스와 피드는 받을 수 있는 지표가 다르다.
	 * 한 번에 요청했다가 일부 지표가 거부되면 지표별로 다시 요청하고, 거부된 지표는 이후 요청에서 뺀다.
	 */
	public MediaInsights readInsights(MediaItem media, ReadContext ctx) {
		List<String> metrics = media.isReels() ? REELS_METRICS : FEED_METRICS;
		List<String> requested = metrics.stream().filter(m -> !ctx.isUnsupported(media.productType(), m)).toList();
		if (requested.isEmpty()) {
			return MediaInsights.failed("지원되는 지표 없음");
		}
		try {
			JsonNode node = get(ctx, media.id() + "/insights", Map.of("metric", String.join(",", requested)));
			return MediaInsights.from(parseMetricValues(node));
		}
		catch (GraphApiException e) {
			throwIfFatal(e);
			if (e.getType() != GraphApiErrorType.INVALID_REQUEST || requested.size() == 1) {
				return MediaInsights.failed(e.getDetail());
			}
			return readInsightsOneByOne(media, requested, ctx);
		}
	}

	private MediaInsights readInsightsOneByOne(MediaItem media, List<String> metrics, ReadContext ctx) {
		Map<String, Number> values = new LinkedHashMap<>();
		String lastError = null;
		for (String metric : metrics) {
			try {
				values.putAll(parseMetricValues(get(ctx, media.id() + "/insights", Map.of("metric", metric))));
			}
			catch (GraphApiException e) {
				throwIfFatal(e);
				lastError = e.getDetail();
				if (e.getType() == GraphApiErrorType.INVALID_REQUEST) {
					ctx.markUnsupported(media.productType(), metric);
					log.info("{} 게시물에서 지원하지 않는 지표로 기록: {}", media.productType(), metric);
				}
			}
		}
		return values.isEmpty() ? MediaInsights.failed(lastError) : MediaInsights.from(values);
	}

	/** 팔로워 연령·성별, 참여자 연령. 팔로워·참여가 100 미만이면 Meta가 주지 않으므로 빈 결과일 수 있다. */
	public List<DemographicRow> readDemographics(String igUserId, ReadContext ctx) {
		List<DemographicRow> rows = new ArrayList<>();
		readDemographic(igUserId, "follower_demographics", "age", ctx, rows);
		readDemographic(igUserId, "follower_demographics", "gender", ctx, rows);
		readDemographic(igUserId, "engaged_audience_demographics", "age", ctx, rows);
		return rows;
	}

	private void readDemographic(String igUserId, String metric, String breakdown, ReadContext ctx,
			List<DemographicRow> rows) {
		try {
			JsonNode node = get(ctx, igUserId + "/insights", Map.of("metric", metric, "period", "lifetime",
					"metric_type", "total_value", "timeframe", "this_month", "breakdown", breakdown));
			for (JsonNode item : node.path("data")) {
				for (JsonNode b : item.path("total_value").path("breakdowns")) {
					for (JsonNode r : b.path("results")) {
						rows.add(new DemographicRow(metric, breakdown, r.path("dimension_values").path(0).asString(""),
								r.path("value").asInt(0)));
					}
				}
			}
		}
		catch (GraphApiException e) {
			throwIfFatal(e);
			log.info("연령대 {}/{} 를 받지 못함: {}", metric, breakdown, e.getDetail());
		}
	}

	private JsonNode get(ReadContext ctx, String path, Map<String, String> params) {
		ctx.countCall();
		return client.get(path, params);
	}

	private static void throwIfFatal(GraphApiException e) {
		if (FATAL.contains(e.getType())) {
			throw e;
		}
	}

	static Map<String, Number> parseMetricValues(JsonNode node) {
		Map<String, Number> values = new LinkedHashMap<>();
		for (JsonNode item : node.path("data")) {
			JsonNode value = item.has("values") ? item.path("values").path(0).path("value")
					: item.path("total_value").path("value");
			if (value.isNumber()) {
				values.put(item.path("name").asString(), value.numberValue());
			}
		}
		return values;
	}

	static MediaItem toMediaItem(JsonNode m) {
		return new MediaItem(m.path("id").asString(), m.path("media_type").asString(""),
				m.path("media_product_type").asString(""), parseTime(m.path("timestamp").asString(null)),
				textOrNull(m, "caption"), textOrNull(m, "thumbnail_url"), textOrNull(m, "media_url"),
				textOrNull(m, "permalink"), intOrNull(m, "like_count"), intOrNull(m, "comments_count"));
	}

	static Instant parseTime(String value) {
		return value == null ? null : OffsetDateTime.parse(value, GRAPH_TIME).toInstant();
	}

	private static String textOrNull(JsonNode node, String field) {
		JsonNode v = node.path(field);
		return v.isMissingNode() || v.isNull() ? null : v.asString();
	}

	private static Integer intOrNull(JsonNode node, String field) {
		JsonNode v = node.path(field);
		return v.isNumber() ? v.asInt() : null;
	}
}
