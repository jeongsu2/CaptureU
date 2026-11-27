package com.captureu.recommendation;

import static com.captureu.recommendation.ReportFixtures.account;
import static com.captureu.recommendation.ReportFixtures.card;
import static com.captureu.recommendation.ReportFixtures.report;
import static com.captureu.recommendation.ReportFixtures.stat;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.captureu.analysis.CaptionHook.CallToAction;
import com.captureu.analysis.CaptionHook.HookType;
import com.captureu.analysis.ReportService.AccountReport;
import com.captureu.analysis.ReportService.FailedReference;
import com.captureu.recommendation.PatternAnalyzer.HookPattern;
import com.captureu.recommendation.PatternAnalyzer.PatternSummary;
import com.captureu.recommendation.PatternAnalyzer.Scope;

class PatternAnalyzerTest {

	// 전체 40개 중 아웃라이어 8개(20%)인 계정들
	private final AccountReport own = account("me", true, 40, 8,
			List.of(stat(HookType.LIST, 10, 5), stat(HookType.QUESTION, 10, 2), stat(HookType.OTHER, 20, 1)),
			List.of(card("겨울 기본템 3가지", HookType.LIST, 4.0, CallToAction.NONE)));
	private final AccountReport refA = account("ref.a", false, 40, 8,
			List.of(stat(HookType.QUESTION, 9, 4), stat(HookType.STORY, 5, 3)),
			List.of(card("이거 저만 몰랐나요?", HookType.QUESTION, 5.0, CallToAction.SAVE_SHARE),
					card("제 실패담 들어보실래요?", HookType.QUESTION, 3.0, CallToAction.SAVE_SHARE)));
	private final AccountReport refB = account("ref.b", false, 40, 8, List.of(stat(HookType.QUESTION, 7, 3)),
			List.of(card("어떡할거야", HookType.QUESTION, 6.0, CallToAction.SAVE_SHARE),
					card("어디서 샀냐면", HookType.QUESTION, 2.5, CallToAction.SAVE_SHARE)));
	// 질문형이 있지만 아웃라이어 비율이 계정 평균(20%)과 같아 잘된 유형이 아님
	private final AccountReport refC = account("ref.c", false, 40, 8, List.of(stat(HookType.QUESTION, 10, 2)),
			List.of());

	@Test
	void 참고_계정_2개_이상에서_잘된_유형이_공통_패턴으로_먼저_온다() {
		PatternSummary summary = PatternAnalyzer.analyze(report(own, List.of(refA, refB, refC)));

		assertThat(summary.patterns()).extracting(HookPattern::type, HookPattern::scope)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(HookType.QUESTION, Scope.COMMON),
					org.assertj.core.groups.Tuple.tuple(HookType.LIST, Scope.OWN),
					org.assertj.core.groups.Tuple.tuple(HookType.STORY, Scope.SINGLE));
		HookPattern question = summary.patterns().get(0);
		assertThat(question.references()).extracting(PatternAnalyzer.TypeSignal::username)
			.containsExactly("ref.a", "ref.b");
		assertThat(question.own()).isNull();
		// 배수 높은 예시부터
		assertThat(question.examples()).containsExactly("어떡할거야", "이거 저만 몰랐나요?", "제 실패담 들어보실래요?");
		assertThat(PatternAnalyzer.evidence(question, 3))
			.isEqualTo("참고 계정 3개 중 2개(@ref.a 9개 중 4개, @ref.b 7개 중 3개)에서 잘된 질문형");
		assertThat(PatternAnalyzer.evidence(summary.patterns().get(2), 3)).endsWith("(이 계정 스타일)");
		assertThat(summary.fewReferences()).isFalse();
		assertThat(summary.saveShareCommon()).isTrue();
		assertThat(summary.notes()).isEmpty();
	}

	@Test
	void 내_계정에서도_잘된_공통_유형은_근거에_함께_적는다() {
		AccountReport ownQuestion = account("me", true, 40, 8, List.of(stat(HookType.QUESTION, 10, 5)), List.of());

		HookPattern question = PatternAnalyzer.analyze(report(ownQuestion, List.of(refA, refB))).patterns().get(0);

		assertThat(question.scope()).isEqualTo(Scope.COMMON);
		assertThat(PatternAnalyzer.evidence(question, 2))
			.isEqualTo("참고 계정 2개 중 2개(@ref.a 9개 중 4개, @ref.b 7개 중 3개)와 내 계정(10개 중 5개가 아웃라이어, 평균의 2.5배)에서 잘된 질문형");
	}

	@Test
	void 예시는_그_패턴이_잘된_계정의_문장만_쓴다() {
		// ref.c는 질문형이 잘된 계정이 아니므로 ref.c의 질문형 카드는 예시에서 빠진다
		AccountReport refCWithCard = account("ref.c", false, 40, 8, List.of(stat(HookType.QUESTION, 10, 2)),
				List.of(card("ref.c의 질문?", HookType.QUESTION, 9.0, CallToAction.NONE)));

		HookPattern question = PatternAnalyzer.analyze(report(own, List.of(refA, refB, refCWithCard))).patterns().get(0);

		assertThat(question.examples()).doesNotContain("ref.c의 질문?");
	}

	@Test
	void 표본이_적은_내_계정은_패턴_근거로_쓰지_않는다() {
		AccountReport small = account("me", true, 4, 2, List.of(stat(HookType.LIST, 3, 2)), List.of());

		assertThat(PatternAnalyzer.analyze(report(small, List.of())).patterns()).isEmpty();
	}

	@Test
	void 참고_계정이_하나면_공통_패턴이_없고_치우침을_안내한다() {
		PatternSummary summary = PatternAnalyzer.analyze(
				report(own, List.of(refA), new FailedReference("ref.x", "TARGET_NOT_FOUND")));

		assertThat(summary.patterns()).extracting(HookPattern::scope).doesNotContain(Scope.COMMON);
		assertThat(summary.fewReferences()).isTrue();
		assertThat(summary.notes()).anyMatch(n -> n.contains("1개라 한 계정 스타일에 치우칠 수"))
			.anyMatch(n -> n.contains("@ref.x는 수집하지 못해"));
	}

	@Test
	void 표본이_적거나_아웃라이어가_없으면_잘된_유형이_아니다() {
		AccountReport tiny = account("me", true, 4, 2, List.of(stat(HookType.LIST, 2, 2)), List.of());
		AccountReport none = account("ref.a", false, 30, 0, List.of(stat(HookType.QUESTION, 30, 0)), List.of());

		PatternSummary summary = PatternAnalyzer.analyze(report(tiny, List.of(none)));

		assertThat(summary.patterns()).isEmpty();
		assertThat(summary.notes()).anyMatch(n -> n.contains("판정한 릴스가 적어"))
			.anyMatch(n -> n.contains("뚜렷하게 잘된 첫 줄 유형을 찾지 못해"));
	}
}
