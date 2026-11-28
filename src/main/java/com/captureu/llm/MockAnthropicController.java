package com.captureu.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 결제 없이 Claude 호출 흐름을 끝까지 돌려 보는 로컬 목 서버 (mock-llm 프로필에서만 켜진다).
 * <p>
 * Messages API와 같은 모양으로 답한다. 이미지가 있으면 썸네일 특징을, 없으면 첫 줄 후보와 썸네일 구성안을 돌려준다.
 * 내용은 요청에서 뽑은 주제·유형으로 만든 고정 문장이고, 썸네일 특징은 이미지 해시로 정해서 같은 이미지는 늘 같은 답을 받는다.
 * 모델 이름이 {@value #MODEL}이라 가격표에 없어 비용은 기록되지 않는다.
 */
@Profile("mock-llm")
@RestController
@RequestMapping("/mock-anthropic/v1")
public class MockAnthropicController {

	public static final String MODEL = "claude-mock";
	private static final Pattern TOPIC = Pattern.compile("<topic>(.*?)</topic>", Pattern.DOTALL);
	private static final Pattern TYPE = Pattern.compile("(?m)^\\d+\\. ([A-Z_]+) \\[");
	private static final List<String> TYPES = List.of("QUESTION", "TARGET", "LIST", "HOW_TO", "STORY", "ANNOUNCE",
			"OTHER");
	private static final List<String> TONES = List.of("BRIGHT", "DARK", "PASTEL", "VIVID", "NEUTRAL");
	private static final List<String> COMPOSITIONS = List.of("CENTER_SUBJECT", "FULL_BODY", "FLAT_LAY", "SPLIT",
			"TEXT_CARD", "SCENE", "OTHER");

	private final JsonMapper json = JsonMapper.builder().build();

	@PostMapping("/messages")
	public Map<String, Object> messages(@RequestBody JsonNode request) {
		String image = null;
		StringBuilder text = new StringBuilder();
		for (JsonNode message : request.path("messages")) {
			JsonNode content = message.path("content");
			if (content.isString()) {
				text.append(content.asString());
			}
			for (JsonNode block : content) {
				if ("image".equals(block.path("type").asString())) {
					image = block.path("source").path("data").asString();
				}
				else if ("text".equals(block.path("type").asString())) {
					text.append(block.path("text").asString());
				}
			}
		}
		Object body = image != null ? thumbnailTags(image) : hookDraft(text.toString());
		String answer = json.writeValueAsString(body);
		return Map.of("id", "msg_mock", "type", "message", "role", "assistant", "model", MODEL, "content",
				List.of(Map.of("type", "text", "text", answer)), "stop_reason", "end_turn", "usage",
				Map.of("input_tokens", text.length() / 2 + (image == null ? 0 : 1500), "output_tokens",
						answer.length() / 2));
	}

	static Map<String, Object> hookDraft(String prompt) {
		Matcher m = TOPIC.matcher(prompt);
		String topic = m.find() ? m.group(1).strip() : "이번 릴스";
		Set<String> types = new LinkedHashSet<>();
		Matcher t = TYPE.matcher(prompt);
		while (t.find()) {
			if (TYPES.contains(t.group(1))) {
				types.add(t.group(1));
			}
		}
		types.addAll(TYPES);
		List<Map<String, Object>> hooks = new ArrayList<>();
		for (String type : types) {
			if (hooks.size() == 5) {
				break;
			}
			hooks.add(Map.of("text", "[목] " + line(type, topic), "type", type, "rationale",
					"목 서버 응답: 분석 결과의 " + type + " 유형을 따른 예시"));
		}
		return Map.of("hooks", hooks, "thumbnail", Map.of("overlayText", "[목] " + topic, "layout",
				"목 서버 응답: 화면 위쪽 1/3에 큰 제목, 가운데 인물", "rationale", "목 서버 응답"));
	}

	private static String line(String type, String topic) {
		return switch (type) {
			case "QUESTION" -> topic + ", 아직도 이렇게 해?";
			case "TARGET" -> topic + " 고민인 사람만 보세요";
			case "LIST" -> topic + " 꼭 알아야 할 3가지";
			case "HOW_TO" -> topic + " 이렇게 하면 돼요";
			case "STORY" -> topic + " 하다가 생긴 일";
			case "ANNOUNCE" -> topic + " 드디어 공개";
			default -> topic + " 정리해 봤어요";
		};
	}

	static Map<String, Object> thumbnailTags(String imageBase64) {
		byte[] h = sha256(imageBase64);
		boolean face = (h[0] & 1) == 1;
		int textSize = Math.floorMod(h[1], 3);
		return Map.of("faceCloseUp", face, "personCount", face ? 1 : Math.floorMod(h[2], 3), "textSize",
				List.of("NONE", "SMALL", "LARGE").get(textSize), "textContent", textSize == 0 ? "" : "목 썸네일",
				"tone", TONES.get(Math.floorMod(h[3], TONES.size())), "composition",
				COMPOSITIONS.get(Math.floorMod(h[4], COMPOSITIONS.size())));
	}

	private static byte[] sha256(String text) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
