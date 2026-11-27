package com.captureu.instagram.account;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 한 번의 분석 동안 쓰는 상태. 외부 API 호출 수, 거부된 지표(다시 요청하지 않을 것),
 * 게시물 목록이 잘렸는지를 기억한다. 게시물 인사이트를 병렬로 부르므로 여러 스레드가 함께 쓴다.
 */
public class ReadContext {

	private final AtomicInteger callCount = new AtomicInteger();
	private volatile boolean mediaTruncated;
	private final Set<String> unsupportedMetrics = ConcurrentHashMap.newKeySet();

	void countCall() {
		callCount.incrementAndGet();
	}

	public int callCount() {
		return callCount.get();
	}

	/** 따로 센 호출 수(예: 참고 계정 하나를 읽는 데 쓴 호출)를 합친다. */
	public void addCalls(int calls) {
		callCount.addAndGet(calls);
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
