package com.captureu.reference;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
			throw new ReferenceAccountRejectedException(
					List.of(new ReferenceRejection(null, "TOO_MANY", "참고 계정은 최대 %d개예요".formatted(MAX_REFERENCES))));
		}

		Instant now = Instant.now(clock);
		List<ReferenceAccount> accepted = new ArrayList<>();
		for (String username : usernames) {
			try {
				ReferenceProfile profile = reader.readProfile(ownerIgUserId, username, new ReadContext());
				if (ownerIgUserId.equals(profile.igId())) {
					rejections.add(new ReferenceRejection(username, "SELF", "내 계정은 참고 계정으로 넣을 수 없어요"));
					continue;
				}
				accepted.add(new ReferenceAccount(ownerIgUserId, profile.username(), profile.igId(),
						profile.followersCount(), now));
			}
			catch (GraphApiException e) {
				if (e.getType() == GraphApiErrorType.TOKEN_INVALID || e.getType() == GraphApiErrorType.RATE_LIMITED) {
					throw e;
				}
				rejections.add(new ReferenceRejection(username, e.getType().name(), reasonFor(e.getType())));
			}
		}
		if (!rejections.isEmpty()) {
			throw new ReferenceAccountRejectedException(rejections);
		}
		return transaction.execute(status -> {
			repository.deleteByOwnerIgUserId(ownerIgUserId);
			repository.flush();
			return repository.saveAll(accepted);
		});
	}

	static String reasonFor(GraphApiErrorType type) {
		return switch (type) {
			case TARGET_NOT_FOUND -> "개인 계정이거나 없는 아이디예요. 프로페셔널(비즈니스·크리에이터) 계정 아이디를 넣어 주세요";
			case PERMISSION_DENIED -> "이 계정의 정보를 볼 권한이 없어요";
			default -> "지금은 이 계정을 조회할 수 없어요. 잠시 후 다시 시도해 주세요";
		};
	}

	public record ReferenceRejection(String username, String reason, String message) {
	}
}
