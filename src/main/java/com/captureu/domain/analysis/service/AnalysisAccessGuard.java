package com.captureu.domain.analysis.service;

import org.springframework.stereotype.Component;

import com.captureu.domain.analysis.entity.Analysis;
import com.captureu.domain.analysis.exception.AnalysisNotFoundException;
import com.captureu.domain.analysis.repository.AnalysisRepository;
import com.captureu.domain.auth.service.CurrentAccountResolver;

/**
 * 분석 ID로 들어오는 요청이 지금 계정의 분석인지 확인한다 (#16).
 * 남의 분석이면 있는지조차 알리지 않도록 "없음"과 같은 응답(404)을 낸다.
 */
@Component
public class AnalysisAccessGuard {

	private final AnalysisRepository analysisRepository;
	private final CurrentAccountResolver accounts;

	public AnalysisAccessGuard(AnalysisRepository analysisRepository, CurrentAccountResolver accounts) {
		this.analysisRepository = analysisRepository;
		this.accounts = accounts;
	}

	public void check(Long analysisId) {
		String owner = accounts.current().igUserId();
		Analysis analysis = analysisRepository.findById(analysisId)
			.orElseThrow(() -> new AnalysisNotFoundException(analysisId));
		if (!owner.equals(analysis.getIgUserId())) {
			throw new AnalysisNotFoundException(analysisId);
		}
	}
}
