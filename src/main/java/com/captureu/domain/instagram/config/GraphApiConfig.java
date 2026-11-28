package com.captureu.domain.instagram.config;

import java.net.http.HttpClient;
import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.captureu.domain.instagram.client.GraphApiClient;
import com.captureu.domain.instagram.client.GraphApiUsageTracker;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GraphApiProperties.class)
public class GraphApiConfig {

	@Bean
	GraphApiUsageTracker graphApiUsageTracker(ObjectMapper objectMapper) {
		return new GraphApiUsageTracker(objectMapper, Clock.systemUTC());
	}

	@Bean
	GraphApiClient graphApiClient(RestClient.Builder restClientBuilder, GraphApiProperties properties,
			GraphApiUsageTracker usageTracker, ObjectMapper objectMapper) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());
		return new GraphApiClient(restClientBuilder.clone().requestFactory(requestFactory), properties, usageTracker,
				objectMapper);
	}
}
