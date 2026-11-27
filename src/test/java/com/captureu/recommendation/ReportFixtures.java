package com.captureu.recommendation;

import java.util.List;

import com.captureu.analysis.CaptionHook.CallToAction;
import com.captureu.analysis.CaptionHook.HookType;
import com.captureu.analysis.ReportService.AccountReport;
import com.captureu.analysis.ReportService.AnalysisReport;
import com.captureu.analysis.ReportService.FailedReference;
import com.captureu.analysis.ReportService.HookTypeStat;
import com.captureu.analysis.ReportService.PostCard;

/** 추천 테스트용 리포트. 숫자만 필요한 만큼 채운다. */
final class ReportFixtures {

	private ReportFixtures() {
	}

	static AnalysisReport report(AccountReport own, List<AccountReport> references, FailedReference... failed) {
		return new AnalysisReport(1L, own, null, references, List.of(failed), references.size() < 3);
	}

	/** 판정 judged개 중 아웃라이어 outliers개인 계정. */
	static AccountReport account(String username, boolean mine, int judged, int outliers, List<HookTypeStat> stats,
			List<PostCard> cards) {
		return new AccountReport(username, mine, judged, 0, 1000.0, 1500.0, outliers, cards, 0, judged < 5, stats);
	}

	static HookTypeStat stat(HookType type, int count, int outlierCount) {
		return new HookTypeStat(type, count, outlierCount, 100.0 * outlierCount / count, 0);
	}

	static PostCard card(String hook, HookType type, double multiple, CallToAction cta) {
		return new PostCard("m-" + hook.hashCode(), null, null, null, hook, hook, type, false, cta, null, 1000,
				multiple, null, null, null, null, null, null, null);
	}
}
