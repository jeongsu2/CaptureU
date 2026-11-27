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
 * 계정마다 외부 호출 수와 걸린 시간을 남긴다 (공유 캐시 #9의 전후 비교).
 */
@Component
class ReferenceCollector {

	private static final Logger log = LoggerFactory.getLogger(ReferenceCollector.class);

	private final ReferenceAccountRepository referenceRepository;
	private final ReferenceAccountReader reader;
	private final int maxMedia;

	ReferenceCollector(ReferenceAccountRepository referenceRepository, ReferenceAccountReader reader,
			@Value("${captureu.reference.max-media:100}") int maxMedia) {
		if (maxMedia < 1) {
			throw new IllegalArgumentException("captureu.reference.max-media는 1 이상이어야 합니다: " + maxMedia);
		}
		this.referenceRepository = referenceRepository;
		this.reader = reader;
		this.maxMedia = maxMedia;
	}

	List<CollectedReference> collect(String ownerIgUserId, ReadContext ctx) {
		List<CollectedReference> results = new ArrayList<>();
		for (ReferenceAccount reference : referenceRepository.findByOwnerIgUserIdOrderByIdAsc(ownerIgUserId)) {
			String username = reference.getUsername();
			int callsBefore = ctx.callCount();
			long start = System.nanoTime();
			try {
				ReferenceAccountData data = reader.read(ownerIgUserId, username, maxMedia, ctx);
				results.add(new CollectedReference(username, data, null, null, ctx.callCount() - callsBefore,
						elapsedMs(start)));
			}
			catch (GraphApiException e) {
				if (e.getType() == GraphApiErrorType.TOKEN_INVALID || e.getType() == GraphApiErrorType.RATE_LIMITED) {
					throw e;
				}
				log.info("참고 계정 @{} 수집 실패: {}", username, e.getMessage());
				results.add(new CollectedReference(username, null, e.getType().name(),
						"code=%s subcode=%s: %s".formatted(e.getCode(), e.getSubcode(), e.getDetail()),
						ctx.callCount() - callsBefore, elapsedMs(start)));
			}
			catch (RuntimeException e) {
				// 참고 계정 하나 때문에 내 계정 분석까지 버리지 않는다
				log.warn("참고 계정 @{} 수집 중 예상하지 못한 오류", username, e);
				results.add(new CollectedReference(username, null, "UNEXPECTED", e.getClass().getSimpleName(),
						ctx.callCount() - callsBefore, elapsedMs(start)));
			}
		}
		return results;
	}

	private static long elapsedMs(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	/** 성공이면 data가 있고, 실패면 failureType이 있다. */
	record CollectedReference(String username, ReferenceAccountData data, String failureType, String failureMessage,
			int apiCallCount, long durationMs) {

		boolean succeeded() {
			return data != null;
		}
	}
}
