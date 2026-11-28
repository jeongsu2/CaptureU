package com.captureu.domain.analysis.service;

import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.captureu.domain.instagram.client.InstagramAccountReader;
import com.captureu.domain.instagram.client.ReadContext;
import com.captureu.domain.instagram.dto.AccountInfo;
import com.captureu.domain.instagram.dto.DemographicRow;
import com.captureu.domain.instagram.dto.MediaInsights;
import com.captureu.domain.instagram.dto.MediaItem;

/**
 * 내 계정 데이터를 한 번에 모은다. DB 트랜잭션 밖에서 실행한다 (외부 호출이 길어서).
 * 게시물 인사이트는 서로 기다릴 이유가 없으므로 동시 호출 수를 제한해 병렬로 부른다 (#30).
 * 동시 호출 수를 제한하는 이유: 호출 한도(시간당 호출 수)를 한꺼번에 쓰지 않고, Meta 쪽에 부담을 주지 않기 위해서.
 */
@Component
public class OwnAccountCollector {

	/** 멈춘 뒤 남은 작업이 끝나기를 기다리는 최대 시간. 호출 수 집계가 실패 기록보다 늦게 바뀌지 않도록. */
	private static final long TERMINATION_WAIT_SECONDS = 2;

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
	 * 게시물 순서를 유지한 채 인사이트를 병렬로 받는다.
	 * 끝나는 순서대로 결과를 확인하므로, 어느 게시물에서든 분석 전체를 멈춰야 하는 에러(토큰 무효·호출 한도 초과)가 나면
	 * 바로 멈춤 신호를 남기고 남은 호출을 취소한다. 앞 게시물이 느려도 뒤의 에러를 기다리지 않는다.
	 */
	private List<CollectedMedia> readInsightsInParallel(List<MediaItem> media, ReadContext ctx) {
		if (media.isEmpty()) {
			return List.of();
		}
		ExecutorService executor = Executors.newFixedThreadPool(Math.min(concurrency, media.size()),
				Thread.ofVirtual().name("insight-", 0).factory());
		try {
			CompletionService<Indexed> completion = new ExecutorCompletionService<>(executor);
			for (int i = 0; i < media.size(); i++) {
				int index = i;
				MediaItem item = media.get(i);
				completion.submit(() -> new Indexed(index, reader.readInsights(item, ctx)));
			}
			MediaInsights[] results = new MediaInsights[media.size()];
			for (int done = 0; done < media.size(); done++) {
				Indexed result = await(completion, ctx);
				results[result.index()] = result.insights();
			}
			CollectedMedia[] collected = new CollectedMedia[media.size()];
			for (int i = 0; i < media.size(); i++) {
				collected[i] = new CollectedMedia(media.get(i), results[i]);
			}
			return List.of(collected);
		}
		finally {
			// 정상 종료면 이미 모두 끝났고, 에러로 빠져나오면 남은 호출을 취소하고 잠깐 기다린다
			executor.shutdownNow();
			awaitQuietly(executor);
		}
	}

	private static Indexed await(CompletionService<Indexed> completion, ReadContext ctx) {
		try {
			return completion.take().get();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			IllegalStateException cancelled = new IllegalStateException("인사이트 수집 중 스레드가 중단됨", e);
			ctx.abort(cancelled);
			throw cancelled;
		}
		catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException r) {
				ctx.abort(r);
				throw r;
			}
			if (cause instanceof Error err) {
				throw err;
			}
			throw new IllegalStateException(cause);
		}
	}

	private static void awaitQuietly(ExecutorService executor) {
		try {
			executor.awaitTermination(TERMINATION_WAIT_SECONDS, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private record Indexed(int index, MediaInsights insights) {
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
