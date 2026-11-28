package com.captureu.domain.recommendation.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.captureu.domain.analysis.service.ReportService.AccountReport;
import com.captureu.domain.analysis.service.ReportService.AnalysisReport;
import com.captureu.domain.analysis.service.ReportService.HookTypeStat;
import com.captureu.domain.analysis.service.ReportService.PostCard;
import com.captureu.domain.analysis.util.CaptionHook.CallToAction;
import com.captureu.domain.analysis.util.CaptionHook.HookType;
import com.captureu.domain.reference.service.ReferenceAccountService;

/**
 * 리포트에서 "어떤 첫 줄 유형이 잘됐나"를 계정마다 뽑고, 계정끼리 겹치는지 본다 (#13).
 * <p>
 * 한 계정에서 어떤 유형이 "잘된 유형"이려면: 그 유형 릴스가 {@value #MIN_COUNT}개 이상이고, 그중 아웃라이어가
 * {@value #MIN_OUTLIERS}개 이상이며, 아웃라이어 비율이 그 계정 전체 비율의 {@value #MIN_LIFT}배 이상.
 * 참고 계정 {@value #COMMON_MIN_ACCOUNTS}개 이상에서 잘된 유형은 공통 패턴(COMMON)으로 먼저 추천하고,
 * 한 계정에서만 잘된 유형은 그 계정 스타일(SINGLE)로 따로 표시한다.
 */
public final class PatternAnalyzer {

	static final int MIN_COUNT = 3;
	static final int MIN_OUTLIERS = 2;
	static final double MIN_LIFT = 1.2;
	static final int COMMON_MIN_ACCOUNTS = 2;
	static final int MAX_EXAMPLES = 3;

	private PatternAnalyzer() {
	}

	public enum Scope {
		/** 참고 계정 2개 이상에서 잘됨 */
		COMMON,
		/** 참고 계정 공통은 아니지만 내 계정에서 잘됨 */
		OWN,
		/** 참고 계정 한 곳에서만 잘됨 (그 계정 스타일) */
		SINGLE
	}

	/**
	 * @param rate 이 유형의 아웃라이어 비율(%)
	 * @param lift 계정 전체 아웃라이어 비율 대비 배수
	 */
	public record TypeSignal(String username, int count, int outlierCount, double rate, double lift) {
	}

	/**
	 * @param own        내 계정에서 잘됐으면 그 근거, 아니면 null
	 * @param references 이 유형이 잘된 참고 계정들
	 * @param examples   이 유형으로 잘된 실제 첫 줄 (말투 참고용)
	 */
	public record HookPattern(HookType type, Scope scope, TypeSignal own, List<TypeSignal> references,
			List<String> examples) {
	}

	/**
	 * @param referenceCount  비교된 참고 계정 수
	 * @param fewReferences   권장(3개)보다 적어 한 계정 스타일에 치우칠 수 있음
	 * @param saveShareCommon 여러 계정의 잘된 게시물이 "(저장•공유)" 같은 머리말을 자주 씀
	 */
	public record PatternSummary(List<HookPattern> patterns, int referenceCount, boolean fewReferences,
			boolean saveShareCommon, List<String> notes) {
	}

	public static PatternSummary analyze(AnalysisReport report) {
		List<AccountReport> references = report.references();
		// 판정한 릴스가 적은 내 계정은 참고용이라 패턴 근거로 쓰지 않는다
		Map<HookType, TypeSignal> ownStrong = report.own().lowSample() ? Map.of() : strongTypes(report.own());
		Map<HookType, List<TypeSignal>> refStrong = new EnumMap<>(HookType.class);
		for (AccountReport ref : references) {
			strongTypes(ref).forEach((type, signal) -> refStrong.computeIfAbsent(type, k -> new ArrayList<>()).add(signal));
		}

		Set<HookType> types = new LinkedHashSet<>(ownStrong.keySet());
		types.addAll(refStrong.keySet());
		List<HookPattern> patterns = new ArrayList<>();
		for (HookType type : types) {
			TypeSignal own = ownStrong.get(type);
			List<TypeSignal> refs = refStrong.getOrDefault(type, List.of());
			Scope scope = refs.size() >= COMMON_MIN_ACCOUNTS ? Scope.COMMON : own != null ? Scope.OWN : Scope.SINGLE;
			Set<String> sources = new java.util.HashSet<>();
			refs.forEach(s -> sources.add(s.username()));
			if (own != null) {
				sources.add(own.username());
			}
			patterns.add(new HookPattern(type, scope, own, List.copyOf(refs), examples(report, type, sources)));
		}
		patterns.sort(ORDER);

		boolean few = references.size() < ReferenceAccountService.RECOMMENDED_REFERENCES;
		return new PatternSummary(List.copyOf(patterns), references.size(), few, saveShareCommon(report),
				notes(report, patterns, few));
	}

	/** 공통 → 내 계정 → 한 계정 순. 같은 범위면 내 계정에서도 잘된 것, 근거 계정이 많은 것, 배수가 큰 것 먼저. */
	private static final Comparator<HookPattern> ORDER = Comparator.comparing(HookPattern::scope)
		.thenComparing(p -> p.own() == null)
		.thenComparing(p -> -p.references().size())
		.thenComparing(p -> -maxLift(p));

	private static double maxLift(HookPattern p) {
		double max = p.own() == null ? 0 : p.own().lift();
		for (TypeSignal s : p.references()) {
			max = Math.max(max, s.lift());
		}
		return max;
	}

	static Map<HookType, TypeSignal> strongTypes(AccountReport account) {
		Map<HookType, TypeSignal> strong = new EnumMap<>(HookType.class);
		if (account.judgedCount() == 0 || account.outlierCount() == 0 || account.hookTypes() == null) {
			return strong;
		}
		double baseRate = 100.0 * account.outlierCount() / account.judgedCount();
		for (HookTypeStat stat : account.hookTypes()) {
			if (stat.type() == HookType.OTHER || stat.count() < MIN_COUNT || stat.outlierCount() < MIN_OUTLIERS) {
				continue;
			}
			double rate = 100.0 * stat.outlierCount() / stat.count();
			double lift = rate / baseRate;
			if (lift >= MIN_LIFT) {
				strong.put(stat.type(), new TypeSignal(account.username(), stat.count(), stat.outlierCount(),
						round1(rate), round1(lift)));
			}
		}
		return strong;
	}

	/** 이 패턴의 근거가 된 계정의 잘된 첫 줄만 예시로 쓴다 (한 계정 스타일에 다른 계정 문장이 섞이지 않게). */
	private static List<String> examples(AnalysisReport report, HookType type, Set<String> sources) {
		List<AccountReport> accounts = new ArrayList<>(report.references());
		accounts.add(report.own());
		return accounts.stream()
			.filter(a -> sources.contains(a.username()))
			.flatMap(a -> a.cards().stream())
			.filter(c -> c.hookType() == type && c.captionHook() != null)
			.sorted(Comparator.comparing(PostCard::multiple, Comparator.nullsLast(Comparator.reverseOrder())))
			.map(PostCard::captionHook)
			.distinct()
			.limit(MAX_EXAMPLES)
			.toList();
	}

	/** 계정별 상위 카드 중 2개 이상이 저장·공유 머리말을 쓴 계정이 2곳 이상이면 true. */
	private static boolean saveShareCommon(AnalysisReport report) {
		List<AccountReport> accounts = new ArrayList<>(report.references());
		accounts.add(report.own());
		long count = accounts.stream()
			.filter(a -> a.cards().stream().filter(c -> c.callToAction() == CallToAction.SAVE_SHARE).count() >= 2)
			.count();
		return count >= 2;
	}

	private static List<String> notes(AnalysisReport report, List<HookPattern> patterns, boolean few) {
		List<String> notes = new ArrayList<>();
		if (few) {
			notes.add("비교한 참고 계정이 %d개라 한 계정 스타일에 치우칠 수 있어요. 3개를 권장해요."
				.formatted(report.references().size()));
		}
		if (report.own().lowSample()) {
			notes.add("내 계정은 판정한 릴스가 적어 내 계정 패턴은 참고용이에요.");
		}
		report.failedReferences()
			.forEach(f -> notes.add("@%s는 수집하지 못해 비교에서 빠졌어요.".formatted(f.username())));
		if (patterns.isEmpty()) {
			notes.add("뚜렷하게 잘된 첫 줄 유형을 찾지 못해 자주 쓰이는 유형으로 추천했어요.");
		}
		return notes;
	}

	private static double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}

	/** 근거 문장. 예: "참고 계정 3개 중 2개(@a 9개 중 4개, @b 7개 중 3개)와 내 계정(12개 중 5개)에서 잘된 질문형" */
	public static String evidence(HookPattern p, int referenceCount) {
		StringBuilder sb = new StringBuilder();
		if (!p.references().isEmpty()) {
			sb.append("참고 계정 %d개 중 %d개(".formatted(referenceCount, p.references().size()));
			sb.append(String.join(", ", p.references().stream().map(PatternAnalyzer::signalText).toList()));
			sb.append(")");
		}
		if (p.own() != null) {
			if (!sb.isEmpty()) {
				sb.append("와 ");
			}
			sb.append("내 계정(%d개 중 %d개가 아웃라이어, 평균의 %.1f배)".formatted(p.own().count(), p.own().outlierCount(),
					p.own().lift()));
		}
		sb.append("에서 잘된 ").append(label(p.type()));
		if (p.scope() == Scope.SINGLE) {
			sb.append(" (이 계정 스타일)");
		}
		return sb.toString();
	}

	private static String signalText(TypeSignal s) {
		return "@%s %d개 중 %d개".formatted(s.username(), s.count(), s.outlierCount());
	}

	public static String label(HookType type) {
		return switch (type) {
			case QUESTION -> "질문형";
			case TARGET -> "대상 지목형";
			case LIST -> "목록형";
			case HOW_TO -> "방법·꿀팁형";
			case STORY -> "이야기·후기형";
			case ANNOUNCE -> "공지·예고형";
			case OTHER -> "기타";
		};
	}
}
