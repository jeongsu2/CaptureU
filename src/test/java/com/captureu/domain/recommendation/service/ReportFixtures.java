package com.captureu.domain.recommendation.service;

import java.util.List;

import com.captureu.domain.analysis.service.ReportService.AccountReport;
import com.captureu.domain.analysis.service.ReportService.AnalysisReport;
import com.captureu.domain.analysis.service.ReportService.FailedReference;
import com.captureu.domain.analysis.service.ReportService.HookTypeStat;
import com.captureu.domain.analysis.service.ReportService.PostCard;
import com.captureu.domain.analysis.util.CaptionHook.CallToAction;
import com.captureu.domain.analysis.util.CaptionHook.HookType;

/** 추천 테스트용 리포트. 숫자만 필요한 만큼 채운다. */
public final class ReportFixtures {

	private ReportFixtures() {
	}

	public static AnalysisReport report(AccountReport own, List<AccountReport> references, FailedReference... failed) {
		return new AnalysisReport(1L, own, null, references, List.of(failed), references.size() < 3);
	}

	/** 판정 judged개 중 아웃라이어 outliers개인 계정. */
	public static AccountReport account(String username, boolean mine, int judged, int outliers, List<HookTypeStat> stats,
			List<PostCard> cards) {
		return new AccountReport(username, mine, judged, 0, 1000.0, 1500.0, outliers, cards, 0, judged < 5, stats);
	}

	public static HookTypeStat stat(HookType type, int count, int outlierCount) {
		return new HookTypeStat(type, count, outlierCount, 100.0 * outlierCount / count, 0);
	}

	public static PostCard card(String hook, HookType type, double multiple, CallToAction cta) {
		return new PostCard("m-" + hook.hashCode(), null, null, null, hook, hook, type, false, cta, null, 1000,
				multiple, null, null, null, null, null, null, null);
	}
}
