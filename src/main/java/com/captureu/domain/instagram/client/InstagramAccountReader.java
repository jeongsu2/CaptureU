package com.captureu.domain.instagram.client;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.captureu.domain.instagram.dto.AccountInfo;
import com.captureu.domain.instagram.dto.DemographicRow;
import com.captureu.domain.instagram.dto.MediaInsights;
import com.captureu.domain.instagram.dto.MediaItem;
import com.captureu.domain.instagram.exception.GraphApiErrorType;
import com.captureu.domain.instagram.exception.GraphApiException;

import tools.jackson.databind.JsonNode;

/**
 * 연결된 내 인스타 계정의 정보, 게시물, 인사이트, 연령대를 Graph API로 읽는다.
 * 저장은 하지 않는다. 호출 수는 {@link ReadContext}에 센다.
 */
@Component
public class InstagramAccountReader {

	private static final Logger log = LoggerFactory.getLogger(InstagramAccountReader.class);

	static final int PAGE_SIZE = 50;
	/** 무한 루프 방지. 50개씩 100페이지 = 5,000개. 넘으면 분석에 '잘림'으로 표시한다. */
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

	/**
	 * 게시물 전체를 커서가 끝날 때까지 받는다. media_count는 실제 개수와 다를 수 있어 쓰지 않는다.
	 * 페이지를 넘기는 사이 새 게시물이 올라오면 같은 게시물이 두 번 올 수 있어 ID로 중복을 없앤다.
	 */
	public List<MediaItem> readAllMedia(String igUserId, ReadContext ctx) {
		Map<String, MediaItem> items = new LinkedHashMap<>();
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
				toMediaItem(m).ifPresent(item -> items.putIfAbsent(item.id(), item));
			}
			JsonNode paging = node.path("paging");
			after = paging.path("cursors").path("after").asString(null);
			// paging.next가 없으면 마지막 페이지. next 값(토큰이 들었던 URL)은 쓰지 않고 커서만 쓴다.
			if (!paging.has("next") || data.isEmpty() || after == null) {
				return new ArrayList<>(items.values());
			}
		}
		ctx.markMediaTruncated();
		log.warn("게시물 페이지가 {}쪽을 넘어 중단함 (받은 게시물 {}개)", MAX_PAGES, items.size());
		return new ArrayList<>(items.values());
	}

	/** 최근 게시물만 한 쪽 받는다 (최신순). 게시 직후 추적(#5)처럼 전체 목록이 필요 없을 때 호출을 아낀다. */
	public List<MediaItem> readRecentMedia(String igUserId, int limit, ReadContext ctx) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("fields", MEDIA_FIELDS);
		params.put("limit", String.valueOf(Math.min(limit, PAGE_SIZE)));
		List<MediaItem> items = new ArrayList<>();
		for (JsonNode m : get(ctx, igUserId + "/media", params).path("data")) {
			toMediaItem(m).ifPresent(items::add);
		}
		return items;
	}

	/**
	 * 게시물 인사이트. 릴스와 피드는 받을 수 있는 지표가 다르다.
	 * 한 번에 요청했다가 거부되면 지표별로 다시 요청한다. 같은 게시물에서 다른 지표는 받았는데
	 * 특정 지표만 거부됐을 때만 "그 지표는 지원 안 됨"으로 기억하고 이후 요청에서 뺀다.
	 * (지표가 전부 거부되면 게시물 쪽 문제라서 기억하지 않는다. 예: 프로페셔널 전환 전 게시물)
	 */
	public MediaInsights readInsights(MediaItem media, ReadContext ctx) {
		List<String> metrics = media.isReels() ? REELS_METRICS : FEED_METRICS;
		List<String> requested = metrics.stream().filter(m -> !ctx.isUnsupported(media, m)).toList();
		if (requested.isEmpty()) {
			return MediaInsights.failed("지원되는 지표 없음");
		}
		try {
			JsonNode node = get(ctx, media.id() + "/insights", Map.of("metric", String.join(",", requested)));
			return MediaInsights.from(parseMetricValues(node));
		}
		catch (GraphApiException e) {
			throwIfFatal(e, ctx);
			if (e.getType() != GraphApiErrorType.INVALID_REQUEST || requested.size() == 1) {
				return MediaInsights.failed(e.getDetail());
			}
			return readInsightsOneByOne(media, requested, ctx);
		}
	}

	/**
	 * 지표별로 다시 받는다. 인사이트를 병렬로 받으므로 다른 게시물이 그사이 "지원 안 됨"으로 기록한 지표는 건너뛰고,
	 * 이 게시물에서 다른 지표를 하나라도 받은 순간 거부된 지표를 바로 기록해 다른 스레드가 같은 호출을 반복하지 않게 한다.
	 */
	private MediaInsights readInsightsOneByOne(MediaItem media, List<String> metrics, ReadContext ctx) {
		Map<String, Number> values = new LinkedHashMap<>();
		List<String> rejected = new ArrayList<>();
		String lastError = null;
		for (String metric : metrics) {
			if (ctx.isUnsupported(media, metric)) {
				continue;
			}
			try {
				values.putAll(parseMetricValues(get(ctx, media.id() + "/insights", Map.of("metric", metric))));
			}
			catch (GraphApiException e) {
				throwIfFatal(e, ctx);
				lastError = e.getDetail();
				if (e.getType() == GraphApiErrorType.INVALID_REQUEST) {
					rejected.add(metric);
				}
			}
			if (!values.isEmpty()) {
				markUnsupported(media, rejected, ctx);
				rejected.clear();
			}
		}
		return values.isEmpty() ? MediaInsights.failed(lastError) : MediaInsights.from(values);
	}

	private static void markUnsupported(MediaItem media, List<String> metrics, ReadContext ctx) {
		for (String metric : metrics) {
			ctx.markUnsupported(media, metric);
			log.info("{}/{} 게시물에서 지원하지 않는 지표로 기록: {}", media.productType(), media.mediaType(), metric);
		}
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
			throwIfFatal(e, ctx);
			log.info("연령대 {}/{} 를 받지 못함: {}", metric, breakdown, e.getDetail());
		}
	}

	private JsonNode get(ReadContext ctx, String path, Map<String, String> params) {
		ctx.beforeCall();
		return client.get(path, params);
	}

	/** 분석 전체를 멈춰야 하는 에러면 같은 분석의 다른 스레드도 멈추도록 신호를 남기고 던진다. */
	private static void throwIfFatal(GraphApiException e, ReadContext ctx) {
		if (FATAL.contains(e.getType())) {
			ctx.abort(e);
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

	/** 형식이 이상한 게시물 하나 때문에 분석 전체가 실패하지 않도록, 읽을 수 없으면 건너뛴다. */
	static Optional<MediaItem> toMediaItem(JsonNode m) {
		String id = m.path("id").asString(null);
		Instant postedAt;
		try {
			postedAt = parseTime(m.path("timestamp").asString(null));
		}
		catch (DateTimeParseException e) {
			postedAt = null;
		}
		if (id == null || postedAt == null) {
			log.warn("게시물을 읽을 수 없어 건너뜀 (id={}, timestamp={})", id, m.path("timestamp").asString(null));
			return Optional.empty();
		}
		return Optional.of(new MediaItem(id, m.path("media_type").asString(""),
				m.path("media_product_type").asString(""), postedAt, textOrNull(m, "caption"),
				textOrNull(m, "thumbnail_url"), textOrNull(m, "media_url"), textOrNull(m, "permalink"),
				intOrNull(m, "like_count"), intOrNull(m, "comments_count")));
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
