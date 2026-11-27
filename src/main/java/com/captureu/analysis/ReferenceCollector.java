package com.captureu.analysis;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.captureu.instagram.account.ReadContext;
import com.captureu.instagram.account.ReferenceAccountReader;
import com.captureu.instagram.account.ReferenceAccountReader.ReferenceAccountData;
import com.captureu.instagram.graph.GraphApiErrorType;
import com.captureu.instagram.graph.GraphApiException;
import com.captureu.reference.ReferenceAccount;
import com.captureu.reference.ReferenceAccountRepository;

/**
 * 등록된 참고 계정들의 최근 게시물을 모은다. 참고 계정 하나가 실패해도(개인 계정으로 바뀜 등) 분석 전체는 계속한다.
 * 토큰 무효·호출 한도 초과는 다른 계정도 똑같이 실패하므로 분석 전체를 멈춘다.
 */
@Component
class ReferenceCollector {

	private static final Logger log = LoggerFactory.getLogger(ReferenceCollector.class);

	private final ReferenceAccountRepository referenceRepository;
	private final ReferenceAccountReader reader;
	private final int maxMedia;

	ReferenceCollector(ReferenceAccountRepository referenceRepository, ReferenceAccountReader reader,
			@Value("${captureu.reference.max-media:100}") int maxMedia) {
		this.referenceRepository = referenceRepository;
		this.reader = reader;
		this.maxMedia = maxMedia;
	}

	List<CollectedReference> collect(String ownerIgUserId, ReadContext ctx) {
		List<CollectedReference> results = new ArrayList<>();
		for (ReferenceAccount reference : referenceRepository.findByOwnerIgUserIdOrderByIdAsc(ownerIgUserId)) {
			try {
				ReferenceAccountData data = reader.read(ownerIgUserId, reference.getUsername(), maxMedia, ctx);
				results.add(CollectedReference.success(reference.getUsername(), data));
			}
			catch (GraphApiException e) {
				if (e.getType() == GraphApiErrorType.TOKEN_INVALID || e.getType() == GraphApiErrorType.RATE_LIMITED) {
					throw e;
				}
				log.info("참고 계정 @{} 수집 실패: {}", reference.getUsername(), e.getMessage());
				results.add(CollectedReference.failure(reference.getUsername(), e.getType().name(),
						"code=%s subcode=%s: %s".formatted(e.getCode(), e.getSubcode(), e.getDetail())));
			}
		}
		return results;
	}

	/** 성공이면 data가 있고, 실패면 failureType이 있다. */
	record CollectedReference(String username, ReferenceAccountData data, String failureType, String failureMessage) {

		static CollectedReference success(String username, ReferenceAccountData data) {
			return new CollectedReference(username, data, null, null);
		}

		static CollectedReference failure(String username, String type, String message) {
			return new CollectedReference(username, null, type, message);
		}

		boolean succeeded() {
			return data != null;
		}
	}
}
