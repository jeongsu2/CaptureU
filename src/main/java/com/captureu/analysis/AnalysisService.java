package com.captureu.analysis;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.captureu.instagram.account.ReadContext;
import com.captureu.instagram.graph.GraphApiException;
import com.captureu.instagram.graph.GraphApiProperties;

/**
 * 분석 요청을 받아 그 시점의 내 계정 데이터를 수집·저장한다.
 * 지금은 요청 안에서 동기로 처리하고, 소요 시간과 외부 호출 수를 기록한다 (비동기화 #18의 '개선 전' 숫자).
 */
@Service
public class AnalysisService {

	private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

	private final OwnAccountCollector collector;
	private final AnalysisWriter writer;
	private final AnalysisRepository analysisRepository;
	private final AccountSnapshotRepository accountSnapshotRepository;
	private final MediaMetricSnapshotRepository snapshotRepository;
	private final GraphApiProperties graphProperties;
	private final Clock clock;

	AnalysisService(OwnAccountCollector collector, AnalysisWriter writer, AnalysisRepository analysisRepository,
			AccountSnapshotRepository accountSnapshotRepository, MediaMetricSnapshotRepository snapshotRepository,
			GraphApiProperties graphProperties, Clock clock) {
		this.collector = collector;
		this.writer = writer;
		this.analysisRepository = analysisRepository;
		this.accountSnapshotRepository = accountSnapshotRepository;
		this.snapshotRepository = snapshotRepository;
		this.graphProperties = graphProperties;
		this.clock = clock;
	}

	public AnalysisResponse requestForConnectedAccount() {
		String igUserId = graphProperties.igUserId();
		if (igUserId == null || igUserId.isBlank()) {
			throw new IllegalStateException("연결된 인스타 계정 ID가 없습니다 (.env의 IG_USER_ID)");
		}
		return request(igUserId);
	}

	public AnalysisResponse request(String igUserId) {
		Analysis analysis = writer.start(igUserId, Instant.now(clock));
		ReadContext ctx = new ReadContext();
		try {
			Instant collectedAt = Instant.now(clock);
			var data = collector.collect(igUserId, ctx);
			analysis = writer.complete(analysis.getId(), data, ctx.callCount(), collectedAt, Instant.now(clock));
			log.info("분석 {} 완료: 게시물 {}개, 인사이트 실패 {}개, 외부 호출 {}회, {}ms", analysis.getId(),
					analysis.getMediaCount(), analysis.getInsightFailedCount(), analysis.getApiCallCount(),
					analysis.getDurationMs());
		}
		catch (GraphApiException e) {
			analysis = writer.fail(analysis.getId(), e.getType().name(), e.getDetail(), ctx.callCount(),
					Instant.now(clock));
			log.warn("분석 {} 실패: {}", analysis.getId(), e.getMessage());
		}
		catch (RuntimeException e) {
			// 예상하지 못한 오류여도 분석이 '수집 중'으로 남지 않게 실패로 기록한다
			writer.fail(analysis.getId(), "UNEXPECTED", e.getClass().getSimpleName(), ctx.callCount(),
					Instant.now(clock));
			throw e;
		}
		return toResponse(analysis);
	}

	@Transactional(readOnly = true)
	public AnalysisResponse get(Long id) {
		return analysisRepository.findById(id)
			.map(this::toResponse)
			.orElseThrow(() -> new AnalysisNotFoundException(id));
	}

	private AnalysisResponse toResponse(Analysis analysis) {
		var account = accountSnapshotRepository.findById(analysis.getId()).orElse(null);
		long reelsWithInsights = analysis.getStatus() == AnalysisStatus.DONE
				? snapshotRepository.findByAnalysisId(analysis.getId())
					.stream()
					.filter(s -> s.getSkipRate() != null)
					.count()
				: 0;
		return AnalysisResponse.of(analysis, account, reelsWithInsights);
	}
}
