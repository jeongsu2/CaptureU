package com.captureu.profile;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.captureu.analysis.AccountNotConnectedException;
import com.captureu.instagram.graph.GraphApiProperties;

/** 크리에이터 컨셉 저장·조회. 지금은 .env의 계정 하나 기준 (사용자 로그인은 #16). */
@Service
public class ProfileService {

	private final CreatorProfileRepository repository;
	private final GraphApiProperties graphProperties;
	private final Clock clock;

	ProfileService(CreatorProfileRepository repository, GraphApiProperties graphProperties, Clock clock) {
		this.repository = repository;
		this.graphProperties = graphProperties;
		this.clock = clock;
	}

	/** 연결된 계정의 컨셉. 아직 고르지 않았으면 비어 있다. */
	@Transactional(readOnly = true)
	public Optional<Concepts> current() {
		String owner = graphProperties.igUserId();
		if (owner == null || owner.isBlank()) {
			return Optional.empty();
		}
		return repository.findById(owner).map(p -> new Concepts(p.getMainConcept(), p.getSubConcept()));
	}

	@Transactional
	public Concepts change(Concept main, Concept sub) {
		if (main == null) {
			throw new IllegalArgumentException("메인 컨셉을 골라 주세요");
		}
		if (main == sub) {
			throw new IllegalArgumentException("2순위 컨셉은 메인과 달라야 해요");
		}
		String owner = graphProperties.igUserId();
		if (owner == null || owner.isBlank()) {
			throw new AccountNotConnectedException();
		}
		repository.upsert(owner, main.name(), sub == null ? null : sub.name(), Instant.now(clock));
		return new Concepts(main, sub);
	}

	/** @param sub 2순위. 없을 수 있다 */
	public record Concepts(Concept main, Concept sub) {

		/** 프롬프트용 한 줄. 예: "메인 뷰티, 2순위 패션" */
		public String describe() {
			return "메인 " + main.label() + (sub == null ? "" : ", 2순위 " + sub.label());
		}
	}
}
