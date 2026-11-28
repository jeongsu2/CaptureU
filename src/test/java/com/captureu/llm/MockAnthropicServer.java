package com.captureu.llm;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/** 테스트에서 실제 SDK 클라이언트가 목 컨트롤러를 HTTP로 부르게 한다 (Spring 없이). */
public final class MockAnthropicServer implements AutoCloseable {

	private final HttpServer server;

	public MockAnthropicServer() throws IOException {
		JsonMapper json = JsonMapper.builder().build();
		MockAnthropicController controller = new MockAnthropicController();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/mock-anthropic/v1/messages", exchange -> {
			var request = json.readTree(exchange.getRequestBody().readAllBytes());
			byte[] bytes = json.writeValueAsString(controller.messages(request)).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
	}

	/** mock-llm 프로필과 같은 설정. */
	public LlmProperties properties() {
		return new LlmProperties("mock-key", "claude-opus-5-5",
				"http://127.0.0.1:" + server.getAddress().getPort() + "/mock-anthropic", Duration.ofSeconds(5), true,
				true);
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
