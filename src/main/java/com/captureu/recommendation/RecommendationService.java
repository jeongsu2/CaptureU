package com.captureu.recommendation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.captureu.analysis.ReportService;
import com.anthropic.errors.AnthropicServiceException;
import com.captureu.recommendation.ClaudeHookWriter.LlmDraft;
import com.captureu.recommendation.ClaudeHookWriter.LlmRefusedException;
import com.captureu.recommendation.PatternAnalyzer.HookPattern;
import com.captureu.recommendation.PatternAnalyzer.PatternSummary;
import com.captureu.recommendation.Recommendation.Generator;

import tools.jackson.databind.json.JsonMapper;

/**
 * 다음 게시물 주제를 받아 첫 줄 후보와 썸네일 구성안을 근거와 함께 추천한다 (#13).
 * API 키가 있으면 Claude로 만들고, 없거나 실패하면 템플릿으로 만든다. 어느 쪽이든 추천은 나온다.
 * LLM 호출(수 초)에 DB 트랜잭션을 걸지 않으려고 이 클래스에는 트랜잭션을 두지 않는다.
 */
@Service
public class RecommendationService {

	private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

	static final String NO_API_KEY = "NO_API_KEY";

	private final ReportService reportService;
	private final ClaudeHookWriter claude;
	private final RecommendationRepository repository;
	private final JsonMapper jsonMapper;
	private final Clock clock;

	RecommendationService(ReportService reportService, ClaudeHookWriter claude, RecommendationRepository repository,
			JsonMapper jsonMapper, Clock clock) {
		this.reportService = reportService;
		this.claude = claude;
		this.repository = repository;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
	}

	public RecommendationResponse recommend(Long analysisId, String topic) {
		String cleanTopic = topic.strip();
		// @NotBlank는 전각 공백(U+3000)을 공백으로 보지 않는다. strip 기준으로 한 번 더 본다
		if (cleanTopic.isEmpty()) {
			throw new InvalidTopicException();
		}
		PatternSummary summary = PatternAnalyzer.analyze(reportService.build(analysisId));

		long start = System.nanoTime();
		Generator generator = Generator.TEMPLATE;
		String model = null;
		String promptVersion = TemplateHookWriter.VERSION;
		Integer inputTokens = null;
		Integer outputTokens = null;
		java.math.BigDecimal cost = null;
		String fallbackReason = null;
		RecommendationDraft draft = null;

		if (claude.enabled()) {
			try {
				LlmDraft llm = claude.write(cleanTopic, summary);
				draft = llm.draft();
				generator = Generator.LLM;
				model = llm.model();
				promptVersion = ClaudeHookWriter.PROMPT_VERSION;
				inputTokens = llm.inputTokens();
				outputTokens = llm.outputTokens();
				cost = llm.costUsd();
			}
			catch (LlmRefusedException e) {
				fallbackReason = "REFUSED";
				log.warn("추천 생성 요청이 거절돼 템플릿으로 추천합니다. analysisId={}", analysisId);
			}
			catch (RuntimeException e) {
				// 외부 오류 원문(요청 ID, 내부 주소 등)은 응답·DB에 남기지 않고 로그로만 본다
				fallbackReason = failureCode(e);
				log.warn("Claude 호출에 실패해 템플릿으로 추천합니다. analysisId={}, reason={}", analysisId, fallbackReason);
				log.debug("Claude 호출 실패 상세", e);
			}
		}
		else {
			fallbackReason = NO_API_KEY;
		}
		if (draft == null) {
			draft = TemplateHookWriter.write(cleanTopic, summary);
		}
		long latencyMs = (System.nanoTime() - start) / 1_000_000;

		RecommendationResult result = new RecommendationResult(draft.hooks(), draft.thumbnail(), summary.patterns(),
				summary.fewReferences(), summary.notes());
		Recommendation saved = repository.save(Recommendation.of(analysisId, cleanTopic, generator, model,
				promptVersion, inputTokens, outputTokens, cost, latencyMs, fallbackReason,
				jsonMapper.writeValueAsString(result), Instant.now(clock)));
		return RecommendationResponse.of(saved, result);
	}

	static String failureCode(RuntimeException e) {
		if (e instanceof AnthropicServiceException service) {
			return "LLM_ERROR: HTTP " + service.statusCode();
		}
		return "LLM_ERROR: " + e.getClass().getSimpleName();
	}

	public RecommendationResponse get(Long id) {
		Recommendation r = repository.findById(id).orElseThrow(() -> new RecommendationNotFoundException(id));
		return RecommendationResponse.of(r, jsonMapper.readValue(r.getResultJson(), RecommendationResult.class));
	}

	/**
	 * @param patterns      추천의 근거가 된 첫 줄 유형 패턴 (공통 → 내 계정 → 한 계정 순)
	 * @param fewReferences 참고 계정이 권장(3개)보다 적어 한 계정 스타일에 치우칠 수 있음
	 */
	public record RecommendationResult(List<RecommendationDraft.HookCandidate> hooks,
			RecommendationDraft.ThumbnailPlan thumbnail, List<HookPattern> patterns, boolean fewReferences,
			List<String> notes) {
	}

	/**
	 * @param fallbackReason LLM 대신 템플릿을 쓴 이유 (NO_API_KEY, REFUSED, LLM_ERROR: ...). LLM으로 만들었으면 null
	 */
	public record RecommendationResponse(Long id, Long analysisId, String topic, Generator generator, String model,
			String promptVersion, Integer inputTokens, Integer outputTokens, java.math.BigDecimal costUsd,
			long latencyMs, String fallbackReason, Instant createdAt, RecommendationResult result) {

		static RecommendationResponse of(Recommendation r, RecommendationResult result) {
			return new RecommendationResponse(r.getId(), r.getAnalysisId(), r.getTopic(), r.getGenerator(),
					r.getModel(), r.getPromptVersion(), r.getInputTokens(), r.getOutputTokens(), r.getCostUsd(),
					r.getLatencyMs(), r.getFallbackReason(), r.getCreatedAt(), result);
		}
	}
}
