package com.captureu.reference;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.captureu.instagram.account.ReadContext;
import com.captureu.instagram.account.ReferenceAccountReader;
import com.captureu.instagram.account.ReferenceAccountReader.ReferenceProfile;
import com.captureu.instagram.graph.GraphApiErrorType;
import com.captureu.instagram.graph.GraphApiException;

/**
 * 참고 계정 등록. 등록할 때 바로 Business Discovery로 조회해 보고, 안 되는 계정은 이유와 함께 거절한다.
 */
@Service
public class ReferenceAccountService {

	public static final int MAX_REFERENCES = 2;

	private final ReferenceAccountRepository repository;
	private final ReferenceAccountReader reader;
	private final TransactionTemplate transaction;
	private final Clock clock;

	ReferenceAccountService(ReferenceAccountRepository repository, ReferenceAccountReader reader,
			TransactionTemplate transaction, Clock clock) {
		this.repository = repository;
		this.reader = reader;
		this.transaction = transaction;
		this.clock = clock;
	}

	public List<ReferenceAccount> list(String ownerIgUserId) {
		return repository.findByOwnerIgUserIdOrderByIdAsc(ownerIgUserId);
	}

	/**
	 * 참고 계정 목록을 통째로 바꾼다. 하나라도 조회할 수 없으면 아무것도 바꾸지 않는다.
	 * 외부 조회는 트랜잭션 밖에서 하고, 모두 통과하면 한 트랜잭션으로 바꾼다.
	 */
	public List<ReferenceAccount> replace(String ownerIgUserId, List<String> rawUsernames) {
		// 같은 사용자가 동시에 바꾸면 2개 제한이 깨지거나 유니크 키가 충돌한다 (버튼 두 번 클릭 등)
		if (!inFlight.add(ownerIgUserId)) {
			throw new ReferenceUpdateInProgressException();
		}
		try {
			return doReplace(ownerIgUserId, rawUsernames);
		}
		finally {
			inFlight.remove(ownerIgUserId);
		}
	}

	private List<ReferenceAccount> doReplace(String ownerIgUserId, List<String> rawUsernames) {
		// 1. 형식 검사: 외부 호출 전에 끝낸다
		Set<String> usernames = new LinkedHashSet<>();
		List<ReferenceRejection> rejections = new ArrayList<>();
		for (String raw : rawUsernames) {
			try {
				usernames.add(ReferenceAccountReader.normalizeUsername(raw));
			}
			catch (IllegalArgumentException e) {
				rejections.add(new ReferenceRejection(raw, "INVALID_USERNAME", "인스타 아이디 형식이 아니에요"));
			}
		}
		if (usernames.size() > MAX_REFERENCES) {
			rejections.add(new ReferenceRejection(null, "TOO_MANY", "참고 계정은 최대 %d개예요".formatted(MAX_REFERENCES)));
		}
		if (!rejections.isEmpty()) {
			throw new ReferenceAccountRejectedException(rejections);
		}

		// 2. 계정마다 조회해 보기. 계정 자체의 문제만 거절 사유로 돌려주고, 일시 장애·토큰·한도는 그대로 올린다
		Instant now = Instant.now(clock);
		List<ReferenceAccount> accepted = new ArrayList<>();
		for (String username : usernames) {
			try {
				ReferenceProfile profile = reader.readProfile(ownerIgUserId, username, new ReadContext());
				if (ownerIgUserId.equals(profile.igId())) {
					rejections.add(new ReferenceRejection(username, "SELF", "내 계정은 참고 계정으로 넣을 수 없어요"));
					continue;
				}
				accepted.add(new ReferenceAccount(ownerIgUserId, username, profile.igId(), profile.followersCount(),
						now));
			}
			catch (GraphApiException e) {
				if (!ACCOUNT_PROBLEMS.contains(e.getType())) {
					throw e;
				}
				rejections.add(new ReferenceRejection(username, e.getType().name(), reasonFor(e.getType())));
			}
		}
		if (!rejections.isEmpty()) {
			throw new ReferenceAccountRejectedException(rejections);
		}

		// 3. 모두 통과하면 한 트랜잭션으로 교체
		return transaction.execute(status -> {
			repository.deleteAllByOwner(ownerIgUserId);
			return repository.saveAll(accepted);
		});
	}

	/** 이 에러들만 '그 계정을 넣을 수 없다'는 사용자 안내로 바꾼다. */
	private static final Set<GraphApiErrorType> ACCOUNT_PROBLEMS = Set.of(GraphApiErrorType.TARGET_NOT_FOUND,
			GraphApiErrorType.PERMISSION_DENIED, GraphApiErrorType.INVALID_REQUEST);

	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

	static String reasonFor(GraphApiErrorType type) {
		return switch (type) {
			case TARGET_NOT_FOUND -> "개인 계정이거나 없는 아이디예요. 프로페셔널(비즈니스·크리에이터) 계정 아이디를 넣어 주세요";
			case PERMISSION_DENIED -> "이 계정의 정보를 볼 권한이 없어요";
			default -> "이 계정은 조회할 수 없어요";
		};
	}

	public record ReferenceRejection(String username, String reason, String message) {
	}
}
