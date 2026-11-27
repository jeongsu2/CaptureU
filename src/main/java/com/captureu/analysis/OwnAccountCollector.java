package com.captureu.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.captureu.instagram.account.AccountInfo;
import com.captureu.instagram.account.DemographicRow;
import com.captureu.instagram.account.InstagramAccountReader;
import com.captureu.instagram.account.MediaInsights;
import com.captureu.instagram.account.MediaItem;
import com.captureu.instagram.account.ReadContext;

/**
 * 내 계정 데이터를 한 번에 모은다. DB 트랜잭션 밖에서 실행한다 (외부 호출이 길어서).
 * 게시물 인사이트는 서로 기다릴 이유가 없으므로 동시 호출 수를 제한해 병렬로 부른다 (#30).
 * 동시 호출 수를 제한하는 이유: 호출 한도(시간당 호출 수)를 한꺼번에 쓰지 않고, Meta 쪽에 부담을 주지 않기 위해서.
 */
@Component
class OwnAccountCollector {

	private final InstagramAccountReader reader;
	private final int concurrency;

	OwnAccountCollector(InstagramAccountReader reader,
			@Value("${captureu.collection.insight-concurrency:8}") int concurrency) {
		if (concurrency < 1) {
			throw new IllegalArgumentException("captureu.collection.insight-concurrency는 1 이상이어야 합니다: " + concurrency);
		}
		this.reader = reader;
		this.concurrency = concurrency;
	}

	CollectedAccount collect(String igUserId, ReadContext ctx) {
		AccountInfo account = reader.readAccount(igUserId, ctx);
		List<MediaItem> media = reader.readAllMedia(igUserId, ctx);
		List<CollectedMedia> collected = readInsightsInParallel(media, ctx);
		List<DemographicRow> demographics = reader.readDemographics(igUserId, ctx);
		return new CollectedAccount(account, collected, demographics, ctx.isMediaTruncated());
	}

	/**
	 * 게시물 순서를 유지한 채 인사이트를 병렬로 받는다. 한 게시물에서 분석 전체를 멈춰야 하는 에러
	 * (토큰 무효·호출 한도 초과)가 나면 남은 호출을 취소하고 그 에러를 그대로 올린다.
	 */
	private List<CollectedMedia> readInsightsInParallel(List<MediaItem> media, ReadContext ctx) {
		if (media.isEmpty()) {
			return List.of();
		}
		ExecutorService executor = Executors.newFixedThreadPool(Math.min(concurrency, media.size()),
				Thread.ofVirtual().name("insight-", 0).factory());
		try {
			List<Future<MediaInsights>> futures = new ArrayList<>(media.size());
			for (MediaItem item : media) {
				futures.add(executor.submit(() -> reader.readInsights(item, ctx)));
			}
			List<CollectedMedia> collected = new ArrayList<>(media.size());
			for (int i = 0; i < media.size(); i++) {
				collected.add(new CollectedMedia(media.get(i), await(futures.get(i))));
			}
			return collected;
		}
		finally {
			// 정상 종료면 이미 모두 끝났고, 에러로 빠져나오면 남은 호출을 취소한다
			executor.shutdownNow();
		}
	}

	private static MediaInsights await(Future<MediaInsights> future) {
		try {
			return future.get();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("인사이트 수집 중 스레드가 중단됨", e);
		}
		catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException r) {
				throw r;
			}
			if (cause instanceof Error err) {
				throw err;
			}
			throw new IllegalStateException(cause);
		}
	}

	/**
	 * @param mediaTruncated 페이지 수 상한에 걸려 게시물을 끝까지 받지 못했는지
	 */
	record CollectedAccount(AccountInfo account, List<CollectedMedia> media, List<DemographicRow> demographics,
			boolean mediaTruncated) {

		int insightFailedCount() {
			return (int) media.stream().filter(m -> m.insights().isFailed()).count();
		}
	}

	record CollectedMedia(MediaItem item, MediaInsights insights) {
	}
}
