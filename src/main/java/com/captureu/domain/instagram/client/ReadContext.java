package com.captureu.domain.instagram.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.captureu.domain.instagram.dto.MediaItem;

/**
 * 한 번의 분석 동안 쓰는 상태. 외부 API 호출 수, 거부된 지표(다시 요청하지 않을 것), 게시물 목록이 잘렸는지,
 * 그리고 <b>분석 전체를 멈춰야 하는지</b>를 기억한다. 게시물 인사이트와 참고 계정을 동시에 읽으므로 여러 스레드가 함께 쓴다.
 * <p>
 * {@link #child()}로 만든 하위 상태(참고 계정 하나 등)는 자기 호출 수를 따로 세면서 상위에도 바로 더하고,
 * 멈춤 신호와 거부된 지표 기록은 상위와 공유한다.
 */
public class ReadContext {

	private final ReadContext parent;
	private final AtomicInteger callCount = new AtomicInteger();
	private final AtomicReference<RuntimeException> abortCause;
	private final Set<String> unsupportedMetrics;
	private volatile boolean mediaTruncated;

	public ReadContext() {
		this(null);
	}

	private ReadContext(ReadContext parent) {
		this.parent = parent;
		this.abortCause = parent == null ? new AtomicReference<>() : parent.abortCause;
		this.unsupportedMetrics = parent == null ? ConcurrentHashMap.newKeySet() : parent.unsupportedMetrics;
	}

	/** 호출 수는 따로 세고 상위에도 더하며, 멈춤 신호는 공유하는 하위 상태. */
	public ReadContext child() {
		return new ReadContext(this);
	}

	/** 외부 호출 직전에 부른다. 이미 멈춤 신호가 있으면 호출하지 않고 그 원인을 던진다. */
	void beforeCall() {
		throwIfAborted();
		callCount.incrementAndGet();
		if (parent != null) {
			parent.addCall();
		}
	}

	private void addCall() {
		callCount.incrementAndGet();
		if (parent != null) {
			parent.addCall();
		}
	}

	public int callCount() {
		return callCount.get();
	}

	/**
	 * 분석 전체를 멈춘다 (토큰 무효·호출 한도 초과 등). 처음 원인만 남긴다.
	 * 이후 같은 분석의 모든 스레드는 다음 외부 호출 직전에 이 원인을 던지고 멈춘다.
	 */
	public void abort(RuntimeException cause) {
		abortCause.compareAndSet(null, cause);
	}

	public boolean isAborted() {
		return abortCause.get() != null;
	}

	public void throwIfAborted() {
		RuntimeException cause = abortCause.get();
		if (cause != null) {
			throw cause;
		}
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
