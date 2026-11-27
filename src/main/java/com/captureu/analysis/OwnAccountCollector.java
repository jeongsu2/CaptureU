package com.captureu.analysis;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.captureu.instagram.account.AccountInfo;
import com.captureu.instagram.account.DemographicRow;
import com.captureu.instagram.account.InstagramAccountReader;
import com.captureu.instagram.account.MediaInsights;
import com.captureu.instagram.account.MediaItem;
import com.captureu.instagram.account.ReadContext;

/**
 * 내 계정 데이터를 한 번에 모은다. DB 트랜잭션 밖에서 실행한다 (외부 호출이 길어서).
 * 지금은 게시물 인사이트를 순서대로 하나씩 부른다. 병렬·비동기화는 측정 후 #18에서 한다.
 */
@Component
class OwnAccountCollector {

	private final InstagramAccountReader reader;

	OwnAccountCollector(InstagramAccountReader reader) {
		this.reader = reader;
	}

	CollectedAccount collect(String igUserId, ReadContext ctx) {
		AccountInfo account = reader.readAccount(igUserId, ctx);
		List<MediaItem> media = reader.readAllMedia(igUserId, ctx);
		List<CollectedMedia> collected = new ArrayList<>(media.size());
		for (MediaItem item : media) {
			collected.add(new CollectedMedia(item, reader.readInsights(item, ctx)));
		}
		List<DemographicRow> demographics = reader.readDemographics(igUserId, ctx);
		return new CollectedAccount(account, collected, demographics, ctx.isMediaTruncated());
	}

	/**
	 * @param mediaTruncated 페이지 수 상한에 걸려 게시물을 끝까지 받지 못했는지
	 */
	record CollectedAccount(AccountInfo account, List<CollectedMedia> media, List<DemographicRow> demographics,
			boolean mediaTruncated) {

		int insightFailedCount() {
			return (int) media.stream().filter(m -> m.insights().isFailed()).count();
		}
	}

	record CollectedMedia(MediaItem item, MediaInsights insights) {
	}
}
