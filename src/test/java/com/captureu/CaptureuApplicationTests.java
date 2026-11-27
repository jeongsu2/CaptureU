package com.captureu;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class CaptureuApplicationTests {

	@Autowired
	HealthEndpoint healthEndpoint;

	@Test
	void contextLoads() {
	}

	@Test
	void MySQL과_Redis에_연결되면_헬스체크가_UP이다() {
		assertThat(healthEndpoint.health().getStatus()).isEqualTo(Status.UP);
	}

}
