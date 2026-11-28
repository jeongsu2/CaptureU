package com.captureu.domain.instagram.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import tools.jackson.databind.json.JsonMapper;

class GraphApiUsageTrackerTest {

	private final GraphApiUsageTracker tracker = new GraphApiUsageTracker(JsonMapper.builder().build(),
			Clock.systemUTC());

	@Test
	void 두_헤더가_모두_있으면_더_높은_사용률을_쓴다() {
		HttpHeaders headers = new HttpHeaders();
		headers.add("x-business-use-case-usage",
				"{\"1\":[{\"type\":\"instagram\",\"call_count\":5,\"total_cputime\":1,\"total_time\":2}]}");
		headers.add("x-app-usage", "{\"call_count\":95,\"total_cputime\":10,\"total_time\":20}");

		assertThat(tracker.parse(headers)).hasValueSatisfying(u -> assertThat(u.maxPercent()).isEqualTo(95));
	}

	@Test
	void 사용_사례_헤더에서_가장_높은_항목을_고른다() {
		HttpHeaders headers = new HttpHeaders();
		headers.add("x-business-use-case-usage", "{\"1\":[{\"call_count\":5}],\"2\":[{\"total_time\":40,"
				+ "\"estimated_time_to_regain_access\":3}]}");

		assertThat(tracker.parse(headers)).hasValueSatisfying(u -> {
			assertThat(u.maxPercent()).isEqualTo(40);
			assertThat(u.regainAccessMinutes()).isEqualTo(3);
		});
	}

	@Test
	void 헤더가_없거나_깨져_있으면_비어_있다() {
		HttpHeaders broken = new HttpHeaders();
		broken.add("x-app-usage", "not-json");

		assertThat(tracker.parse(new HttpHeaders())).isEmpty();
		assertThat(tracker.parse(broken)).isEmpty();
	}
}
