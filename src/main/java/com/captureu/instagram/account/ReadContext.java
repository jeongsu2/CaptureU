package com.captureu.instagram.account;

import java.util.HashSet;
import java.util.Set;

/**
 * 한 번의 분석 동안 쓰는 상태. 외부 API 호출 수, 거부된 지표(다시 요청하지 않을 것),
 * 게시물 목록이 잘렸는지를 기억한다. 한 분석 요청 안에서만 쓰고 공유하지 않는다.
 */
public class ReadContext {

	private int callCount;
	private boolean mediaTruncated;
	private final Set<String> unsupportedMetrics = new HashSet<>();

	void countCall() {
		callCount++;
	}

	public int callCount() {
		return callCount;
	}

	void markMediaTruncated() {
		mediaTruncated = true;
	}

	/** 페이지 수 상한에 걸려 게시물을 끝까지 받지 못했는지. */
	public boolean isMediaTruncated() {
		return mediaTruncated;
	}

	boolean isUnsupported(MediaItem media, String metric) {
		return unsupportedMetrics.contains(key(media, metric));
	}

	void markUnsupported(MediaItem media, String metric) {
		unsupportedMetrics.add(key(media, metric));
	}

	private static String key(MediaItem media, String metric) {
		return media.productType() + ":" + media.mediaType() + ":" + metric;
	}
}
