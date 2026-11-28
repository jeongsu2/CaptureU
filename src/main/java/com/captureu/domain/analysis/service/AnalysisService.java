package com.captureu.domain.analysis.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.captureu.domain.analysis.dto.response.AnalysisResponse;
import com.captureu.domain.analysis.entity.Analysis;
import com.captureu.domain.analysis.entity.AnalysisStatus;
import com.captureu.domain.analysis.exception.AnalysisInProgressException;
import com.captureu.domain.analysis.exception.AnalysisNotFoundException;
import com.captureu.domain.analysis.repository.AccountSnapshotRepository;
import com.captureu.domain.analysis.repository.AnalysisReferenceRepository;
import com.captureu.domain.analysis.repository.AnalysisRepository;
import com.captureu.domain.analysis.repository.MediaMetricSnapshotRepository;
import com.captureu.domain.analysis.service.OwnAccountCollector.CollectedAccount;
import com.captureu.domain.analysis.service.ReferenceCollector.CollectedReference;
import com.captureu.domain.auth.dto.CurrentAccount;
import com.captureu.domain.auth.service.CurrentAccountResolver;
import com.captureu.domain.instagram.client.ReadContext;
import com.captureu.domain.instagram.exception.GraphApiException;

import jakarta.annotation.PreDestroy;

/**
 * 분석 요청을 받아 그 시점의 내 계정 데이터를 수집·저장한다.
 * 지금은 요청 안에서 동기로 처리하고, 소요 시간과 외부 호출 수를 기록한다 (비동기화 #18의 '개선 전' 숫자).
 */
@Service
public class AnalysisService {

	private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

	private final OwnAccountCollector collector;
	private final ReferenceCollector referenceCollector;
	private final AnalysisReferenceRepository analysisReferenceRepository;
	private final AnalysisWriter writer;
	private final AnalysisRepository analysisRepository;
	private final AccountSnapshotRepository accountSnapshotRepository;
	private final MediaMetricSnapshotRepository snapshotRepository;
	private final CurrentAccountResolver accounts;
	private final Clock clock;

	/**
	 * 같은 계정의 분석이 동시에 돌면 같은 게시물을 동시에 저장하다 충돌한다 (버튼 두 번 클릭 등).
	 * 지금은 서버가 한 대라 메모리로 막는다. 서버를 여러 대로 늘리면 분산 락으로 바꾼다.
	 */
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

	/** 참고 계정 수집처럼 요청 처리와 동시에 도는 작업용. 외부 호출을 기다리는 작업이라 가상 스레드를 쓴다. */
	private final ExecutorService backgroundExecutor = Executors.newVirtualThreadPerTaskExecutor();

	@PreDestroy
	void shutdownBackgroundExecutor() {
		backgroundExecutor.shutdownNow();
	}

	AnalysisService(OwnAccountCollector collector, ReferenceCollector referenceCollector,
			AnalysisReferenceRepository analysisReferenceRepository, AnalysisWriter writer,
			AnalysisRepository analysisRepository, AccountSnapshotRepository accountSnapshotRepository,
			MediaMetricSnapshotRepository snapshotRepository, CurrentAccountResolver accounts, Clock clock) {
		this.collector = collector;
		this.referenceCollector = referenceCollector;
		this.analysisReferenceRepository = analysisReferenceRepository;
		this.writer = writer;
		this.analysisRepository = analysisRepository;
		this.accountSnapshotRepository = accountSnapshotRepository;
		this.snapshotRepository = snapshotRepository;
		this.accounts = accounts;
		this.clock = clock;
	}

	@EventListener(ApplicationReadyEvent.class)
	void cleanUpAbandonedAnalyses() {
		int count = writer.abandonCollecting(Instant.now(clock));
		if (count > 0) {
			log.warn("수집 도중 중단된 분석 {}건을 실패로 정리함", count);
		}
	}

	public AnalysisResponse requestForConnectedAccount() {
		CurrentAccount account = accounts.current();
		return request(account.igUserId(), account.accessToken());
	}

	public AnalysisResponse request(String igUserId) {
		return request(igUserId, null);
	}

	/** @param accessToken 로그인 사용자의 토큰 (#16). null이면 .env 토큰 */
	public AnalysisResponse request(String igUserId, String accessToken) {
		if (!inFlight.add(igUserId)) {
			throw new AnalysisInProgressException(igUserId);
		}
		try {
			return collectAndSave(igUserId, accessToken);
		}
		finally {
			inFlight.remove(igUserId);
		}
	}

	private AnalysisResponse collectAndSave(String igUserId, String accessToken) {
		Analysis analysis = writer.start(igUserId, Instant.now(clock));
		ReadContext ctx = new ReadContext(accessToken);
		try {
			Instant collectedAt = Instant.now(clock);
			// 참고 계정은 내 계정과 서로 기다릴 이유가 없으므로 동시에 모은다 (#30).
			// submit이 돌려주는 Future는 cancel(true) 시 실제로 스레드를 중단시킨다 (CompletableFuture는 중단하지 않음)
			Future<List<CollectedReference>> referencesFuture = backgroundExecutor
				.submit(() -> referenceCollector.collect(igUserId, ctx));
			CollectedAccount own;
			try {
				own = collector.collect(igUserId, ctx);
			}
			catch (RuntimeException | Error e) {
				// 같은 분석의 참고 계정 수집도 다음 호출 전에 멈추고, 실패를 기록하기 전에 잠깐 끝나기를 기다린다
				ctx.abort(e instanceof RuntimeException r ? r : new IllegalStateException(e));
				referencesFuture.cancel(true);
				awaitQuietly(referencesFuture);
				throw e;
			}
			List<CollectedReference> references = await(referencesFuture);
			analysis = writer.complete(analysis.getId(), own, references, ctx.callCount(), collectedAt,
					Instant.now(clock));
			log.info("분석 {} 완료: 게시물 {}개, 인사이트 실패 {}개, 참고 계정 {}개, 외부 호출 {}회, {}ms", analysis.getId(),
					analysis.getMediaCount(), analysis.getInsightFailedCount(), references.size(),
					analysis.getApiCallCount(), analysis.getDurationMs());
		}
		catch (GraphApiException e) {
			String message = "code=%s subcode=%s: %s".formatted(e.getCode(), e.getSubcode(), e.getDetail());
			Long id = analysis.getId();
			analysis = withoutInterrupt(
					() -> writer.fail(id, e.getType().name(), message, ctx.callCount(), Instant.now(clock)));
			log.warn("분석 {} 실패: {}", analysis.getId(), e.getMessage());
		}
		catch (Throwable e) {
			// 예상하지 못한 오류(Error 포함)여도 분석이 '수집 중'으로 남지 않게 실패로 기록하고 원래 예외를 올린다
			Long id = analysis.getId();
			try {
				withoutInterrupt(() -> writer.fail(id, "UNEXPECTED", e.getClass().getSimpleName(), ctx.callCount(),
						Instant.now(clock)));
			}
			catch (RuntimeException writeFailure) {
				e.addSuppressed(writeFailure);
			}
			throw e;
		}
		return toResponse(analysis);
	}

	/**
	 * 스레드 중단 표시가 남아 있으면 DB 연결을 얻는 중에 실패할 수 있다.
	 * 실패 기록은 꼭 남아야 하므로 표시를 잠시 지웠다가 되돌린다.
	 */
	private static <T> T withoutInterrupt(Supplier<T> action) {
		boolean interrupted = Thread.interrupted();
		try {
			return action.get();
		}
		finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/** Future가 감싼 예외를 풀어 원래 예외(GraphApiException 등)로 올린다. */
	private static <T> T await(Future<T> future) {
		try {
			return future.get();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("참고 계정 수집을 기다리는 중 스레드가 중단됨", e);
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

	/** 취소한 작업이 끝나기를 잠깐 기다린다. 결과나 예외는 무시한다. */
	private static void awaitQuietly(Future<?> future) {
		try {
			future.get(2, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		catch (ExecutionException | CancellationException | TimeoutException ignored) {
			// 취소했거나 실패했거나 아직 안 끝난 작업
		}
	}

	@Transactional(readOnly = true)
	public AnalysisResponse get(Long id) {
		return analysisRepository.findById(id)
			.map(this::toResponse)
			.orElseThrow(() -> new AnalysisNotFoundException(id));
	}

	private AnalysisResponse toResponse(Analysis analysis) {
		var account = accountSnapshotRepository.findById(analysis.getId()).orElse(null);
		long reelsWithSkipRate = analysis.getStatus() == AnalysisStatus.DONE
				? snapshotRepository.countByAnalysisIdAndSkipRateIsNotNull(analysis.getId()) : 0;
		return AnalysisResponse.of(analysis, account, reelsWithSkipRate,
				analysisReferenceRepository.findByAnalysisIdOrderByIdAsc(analysis.getId()));
	}
}
