package com.captureu.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/** 프로필 설정 파일이 YAML로 읽히는지 (키 중복·들여쓰기 실수는 통합 테스트에서 잡히지 않는다). */
class ApplicationYamlTest {

	@ParameterizedTest
	@ValueSource(strings = { "application.yml", "application-local.yml", "application-mock-llm.yml",
			"application-https.yml" })
	void 설정_파일을_읽을_수_있다(String name) {
		ClassPathResource resource = new ClassPathResource(name);
		assertThat(resource.exists()).isTrue();
		assertThatCode(() -> load(resource)).doesNotThrowAnyException();
	}

	private static void load(ClassPathResource resource) throws IOException {
		new YamlPropertySourceLoader().load(resource.getFilename(), resource);
	}
}
