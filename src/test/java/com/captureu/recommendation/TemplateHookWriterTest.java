package com.captureu.recommendation;

import static com.captureu.recommendation.ReportFixtures.account;
import static com.captureu.recommendation.ReportFixtures.report;
import static com.captureu.recommendation.ReportFixtures.stat;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.captureu.analysis.CaptionHook;
import com.captureu.analysis.CaptionHook.HookType;
import com.captureu.recommendation.PatternAnalyzer.PatternSummary;
import com.captureu.recommendation.RecommendationDraft.HookCandidate;

class TemplateHookWriterTest {

	@Test
	void 잘된_유형_순서대로_후보를_만들고_근거를_붙인다() {
		PatternSummary summary = PatternAnalyzer.analyze(report(
				account("me", true, 40, 8, List.of(stat(HookType.LIST, 10, 5)), List.of()),
				List.of(account("ref.a", false, 40, 8, List.of(stat(HookType.QUESTION, 9, 4)), List.of()),
						account("ref.b", false, 40, 8, List.of(stat(HookType.QUESTION, 7, 3)), List.of()))));

		RecommendationDraft draft = TemplateHookWriter.write("겨울 출근룩", summary);

		assertThat(draft.hooks()).hasSize(5);
		assertThat(draft.hooks()).extracting(HookCandidate::type)
			.containsExactly(HookType.QUESTION, HookType.LIST, HookType.HOW_TO, HookType.QUESTION, HookType.LIST);
		assertThat(draft.hooks().get(0).text()).isEqualTo("겨울 출근룩, 아직도 이렇게 하세요?");
		assertThat(draft.hooks().get(0).rationale()).contains("참고 계정 2개 중 2개");
		// 패턴에 없는 세 번째 자리는 기본 유형으로 채우고 그렇다고 밝힌다
		assertThat(draft.hooks().get(2).rationale()).contains("뚜렷한 근거가 없어");
		assertThat(draft.thumbnail().overlayText()).isEqualTo("겨울 출근룩?");
	}

	@Test
	void 모든_틀은_첫_줄_분류기가_선언한_유형으로_읽는다() {
		// 틀 문장이 실제로 그 유형으로 분류돼야 채택 후 효과 검증(#14)에서 유형별로 셀 수 있다
		for (HookType type : List.of(HookType.QUESTION, HookType.TARGET, HookType.LIST, HookType.HOW_TO,
				HookType.STORY, HookType.ANNOUNCE)) {
			PatternSummary summary = PatternAnalyzer.analyze(report(
					account("me", true, 40, 8, List.of(stat(type, 10, 5)), List.of()), List.of()));

			List<HookCandidate> hooks = TemplateHookWriter.write("겨울 출근룩", summary).hooks();

			assertThat(hooks).filteredOn(h -> h.type() == type)
				.hasSize(2)
				.allSatisfy(h -> assertThat(CaptionHook.parse(h.text()).type()).as(h.text()).isEqualTo(type));
		}
	}

	@Test
	void 한_계정_스타일은_많아야_하나만_쓴다() {
		PatternSummary summary = PatternAnalyzer.analyze(report(account("me", true, 40, 8, List.of(), List.of()),
				List.of(account("ref.a", false, 40, 8, List.of(stat(HookType.STORY, 5, 3)), List.of()),
						account("ref.b", false, 40, 8, List.of(stat(HookType.TARGET, 5, 3)), List.of()),
						account("ref.c", false, 40, 8, List.of(stat(HookType.ANNOUNCE, 5, 3)), List.of()))));

		List<HookCandidate> hooks = TemplateHookWriter.write("겨울 출근룩", summary).hooks();

		assertThat(hooks).extracting(HookCandidate::type)
			.filteredOn(t -> t == HookType.STORY || t == HookType.TARGET || t == HookType.ANNOUNCE)
			.containsOnly(hooks.get(0).type());
		assertThat(hooks).extracting(HookCandidate::type).contains(HookType.QUESTION, HookType.LIST);
	}

	@Test
	void 썸네일_글자는_단어_중간에서_자르지_않는다() {
		assertThat(TemplateHookWriter.shortTopic("겨울 데일리 메이크업")).isEqualTo("겨울 데일리");
		assertThat(TemplateHookWriter.shortTopic("겨울 출근룩")).isEqualTo("겨울 출근룩");
		assertThat(TemplateHookWriter.shortTopic("아주아주아주긴한단어하나")).isEqualTo("아주아주아주긴한단어");
	}

	@Test
	void 패턴이_없으면_기본_유형으로_만든다() {
		PatternSummary summary = PatternAnalyzer.analyze(report(account("me", true, 2, 0, List.of(), List.of()),
				List.of()));

		RecommendationDraft draft = TemplateHookWriter.write("홈카페", summary);

		assertThat(draft.hooks()).extracting(HookCandidate::type)
			.containsExactly(HookType.QUESTION, HookType.LIST, HookType.HOW_TO, HookType.QUESTION, HookType.LIST);
		assertThat(draft.hooks()).allSatisfy(h -> assertThat(h.rationale()).contains("자주 쓰이는"));
	}
}
