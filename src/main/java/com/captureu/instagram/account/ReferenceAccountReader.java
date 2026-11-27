package com.captureu.instagram.account;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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

	/** 최근 게시물을 최대 maxMedia개까지 받는다. 50개씩 커서로 넘긴다. */
	public ReferenceAccountData read(String ownerIgUserId, String username, int maxMedia, ReadContext ctx) {
		int firstLimit = Math.min(PAGE_SIZE, maxMedia);
		JsonNode bd = discovery(ownerIgUserId, username,
				"id,username,followers_count,media_count,media.limit(%d){%s}".formatted(firstLimit, MEDIA_FIELDS), ctx);
		ReferenceProfile profile = toProfile(bd, username);

		Map<String, ReferenceMedia> media = new LinkedHashMap<>();
		JsonNode page = bd.path("media");
		while (true) {
			for (JsonNode m : page.path("data")) {
				InstagramAccountReader.toMediaItem(m)
					.ifPresent(item -> media.putIfAbsent(item.id(), new ReferenceMedia(item, intOrNull(m, "view_count"))));
			}
			String after = page.path("paging").path("cursors").path("after").asString(null);
			boolean hasNext = page.path("paging").has("next") && after != null && !page.path("data").isEmpty();
			if (!hasNext || media.size() >= maxMedia) {
				break;
			}
			int limit = Math.min(PAGE_SIZE, maxMedia - media.size());
			page = discovery(ownerIgUserId, username, "media.after(%s).limit(%d){%s}".formatted(after, limit, MEDIA_FIELDS),
					ctx)
				.path("media");
		}
		List<ReferenceMedia> items = new ArrayList<>(media.values());
		return new ReferenceAccountData(profile, items.size() > maxMedia ? items.subList(0, maxMedia) : items);
	}

	private JsonNode discovery(String ownerIgUserId, String username, String innerFields, ReadContext ctx) {
		String safe = normalizeUsername(username);
		ctx.countCall();
		return client
			.get(ownerIgUserId, Map.of("fields", "business_discovery.username(%s){%s}".formatted(safe, innerFields)))
			.path("business_discovery");
	}

	private static ReferenceProfile toProfile(JsonNode bd, String username) {
		return new ReferenceProfile(bd.path("id").asString(null), bd.path("username").asString(username),
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

	public record ReferenceAccountData(ReferenceProfile profile, List<ReferenceMedia> media) {
	}
}
