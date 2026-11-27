package com.captureu.instagram.account;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.captureu.instagram.graph.GraphApiClient;

import tools.jackson.databind.JsonNode;

/**
 * 다른 프로페셔널 계정(참고 계정)의 공개 정보와 게시물을 Business Discovery로 읽는다.
 * 저장·공유·스킵률은 볼 수 없고, 조회수(릴스)·좋아요(숨기지 않았을 때)·댓글 수·캡션·썸네일만 받는다.
 */
@Component
public class ReferenceAccountReader {

	static final int PAGE_SIZE = 50;

	/** 인스타 아이디 규칙: 영문·숫자·마침표·밑줄, 30자 이하. fields 식에 그대로 들어가므로 반드시 검사한다. */
	private static final Pattern USERNAME = Pattern.compile("[a-z0-9._]{1,30}");

	/** 커서도 fields 식에 들어가므로 base64url 형태만 받는다. */
	private static final Pattern CURSOR = Pattern.compile("[A-Za-z0-9_=-]+");

	private static final Logger log = LoggerFactory.getLogger(ReferenceAccountReader.class);

	private static final String MEDIA_FIELDS = "id,media_type,media_product_type,timestamp,caption,thumbnail_url,"
			+ "media_url,permalink,like_count,comments_count,view_count";

	private final GraphApiClient client;

	public ReferenceAccountReader(GraphApiClient client) {
		this.client = client;
	}

	/** 앞의 @와 공백을 떼고 소문자로 바꾼 뒤 규칙에 맞는지 확인한다. 맞지 않으면 IllegalArgumentException. */
	public static String normalizeUsername(String raw) {
		String username = raw == null ? "" : raw.strip().replaceFirst("^@", "").toLowerCase(Locale.ROOT);
		if (!USERNAME.matcher(username).matches()) {
			throw new IllegalArgumentException("인스타 아이디 형식이 아닙니다: " + raw);
		}
		return username;
	}

	/** 등록할 때 조회 가능 여부만 확인한다 (호출 1회). */
	public ReferenceProfile readProfile(String ownerIgUserId, String username, ReadContext ctx) {
		JsonNode bd = discovery(ownerIgUserId, username, "id,username,followers_count,media_count", ctx);
		return toProfile(bd, username);
	}

	/**
	 * 최근 게시물을 최대 maxMedia개까지 받는다. 50개씩 커서로 넘긴다.
	 * Business Discovery의 media 페이지에는 paging.next가 오지 않고 커서만 온다 (1차 스파이크에서 확인).
	 * 그래서 "커서가 있고, 요청한 만큼 꽉 찬 페이지가 왔고, 새 게시물이 늘었으면" 다음 페이지를 부른다.
	 */
	public ReferenceAccountData read(String ownerIgUserId, String username, int maxMedia, ReadContext ctx) {
		if (maxMedia < 1) {
			throw new IllegalArgumentException("maxMedia는 1 이상이어야 합니다: " + maxMedia);
		}
		int limit = Math.min(PAGE_SIZE, maxMedia);
		JsonNode bd = discovery(ownerIgUserId, username,
				"id,username,followers_count,media_count,media.limit(%d){%s}".formatted(limit, MEDIA_FIELDS), ctx);
		ReferenceProfile profile = toProfile(bd, username);

		Map<String, ReferenceMedia> media = new LinkedHashMap<>();
		JsonNode page = bd.path("media");
		int maxPages = (maxMedia + PAGE_SIZE - 1) / PAGE_SIZE + 1;
		for (int pageNo = 1;; pageNo++) {
			int before = media.size();
			JsonNode data = page.path("data");
			for (JsonNode m : data) {
				InstagramAccountReader.toMediaItem(m)
					.ifPresent(item -> media.putIfAbsent(item.id(), new ReferenceMedia(item, intOrNull(m, "view_count"))));
			}
			String after = page.path("paging").path("cursors").path("after").asString(null);
			boolean fullPage = data.size() >= limit;
			boolean progressed = media.size() > before;
			if (after == null || !fullPage || !progressed || media.size() >= maxMedia || pageNo >= maxPages) {
				break;
			}
			if (!CURSOR.matcher(after).matches()) {
				log.warn("참고 계정 @{} 커서 형식이 예상과 달라 다음 페이지를 받지 않음", username);
				break;
			}
			limit = Math.min(PAGE_SIZE, maxMedia - media.size());
			page = discovery(ownerIgUserId, username, "media.after(%s).limit(%d){%s}".formatted(after, limit, MEDIA_FIELDS),
					ctx)
				.path("media");
		}
		List<ReferenceMedia> items = new ArrayList<>(media.values());
		return new ReferenceAccountData(profile, items.size() > maxMedia ? items.subList(0, maxMedia) : items);
	}

	private JsonNode discovery(String ownerIgUserId, String username, String innerFields, ReadContext ctx) {
		String safe = normalizeUsername(username);
		ctx.beforeCall();
		return client
			.get(ownerIgUserId, Map.of("fields", "business_discovery.username(%s){%s}".formatted(safe, innerFields)))
			.path("business_discovery");
	}

	/** 아이디는 응답 값이 아니라 우리가 검사한 입력값을 쓴다 (이후 호출에서 다시 검사해도 통과하도록). */
	private static ReferenceProfile toProfile(JsonNode bd, String username) {
		return new ReferenceProfile(bd.path("id").asString(null), normalizeUsername(username),
				intOrNull(bd, "followers_count"), intOrNull(bd, "media_count"));
	}

	private static Integer intOrNull(JsonNode node, String field) {
		JsonNode v = node.path(field);
		return v.isNumber() ? v.asInt() : null;
	}

	public record ReferenceProfile(String igId, String username, Integer followersCount, Integer reportedMediaCount) {
	}

	/** @param viewCount 릴스만 온다. 피드는 null */
	public record ReferenceMedia(MediaItem item, Integer viewCount) {
	}

	/** 캐시를 통해 여러 분석이 같은 인스턴스를 함께 쓰므로 목록은 바꿀 수 없게 복사해 둔다. */
	public record ReferenceAccountData(ReferenceProfile profile, List<ReferenceMedia> media) {

		public ReferenceAccountData {
			media = List.copyOf(media);
		}
	}
}
