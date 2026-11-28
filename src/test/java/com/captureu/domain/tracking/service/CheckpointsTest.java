package com.captureu.domain.tracking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CheckpointsTest {

	private final Instant posted = Instant.parse("2026-11-20T00:00:00Z");

	private Instant at(long minutes) {
		return posted.plus(Duration.ofMinutes(minutes));
	}

	@Test
	void 나이가_지난_직후부터_허용_시간_안에만_잰다() {
		assertThat(Checkpoints.due(posted, at(59), Set.of())).isEmpty();
		assertThat(Checkpoints.due(posted, at(60), Set.of())).contains(1);
		assertThat(Checkpoints.due(posted, at(89), Set.of())).contains(1);
		assertThat(Checkpoints.due(posted, at(90), Set.of())).isEmpty();
		// 24시간은 12시간까지 늦어도 잰다
		assertThat(Checkpoints.due(posted, at(24 * 60 + 11 * 60), Set.of())).contains(24);
		assertThat(Checkpoints.due(posted, at(36 * 60), Set.of())).isEmpty();
	}

	@Test
	void 이미_잰_나이는_다시_재지_않는다() {
		assertThat(Checkpoints.due(posted, at(24 * 60 + 5), Set.of(24))).isEmpty();
	}

	@Test
	void 칠일_뒤에는_더_잴_게_없다() {
		assertThat(Checkpoints.finished(posted, at(168 * 60))).isFalse();
		assertThat(Checkpoints.finished(posted, at(180 * 60))).isTrue();
	}

	@Test
	void 같은_나이_과거_중앙값의_2배_이상이면_급상승() {
		var cmp = Checkpoints.compare(3000, List.of(1000, 900, 1200));

		assertThat(cmp.baseline()).isEqualTo(1000.0);
		assertThat(cmp.baselineCount()).isEqualTo(3);
		assertThat(cmp.multiple()).isEqualTo(3.0);
		assertThat(cmp.surge()).isTrue();
		assertThat(Checkpoints.compare(1500, List.of(1000, 900, 1200)).surge()).isFalse();
	}

	@Test
	void 과거_게시물이_3개보다_적으면_비교하지_않는다() {
		var cmp = Checkpoints.compare(3000, Arrays.asList(1000, null, 900));

		assertThat(cmp.baseline()).isNull();
		assertThat(cmp.baselineCount()).isEqualTo(2);
		assertThat(cmp.surge()).isFalse();
	}
}
