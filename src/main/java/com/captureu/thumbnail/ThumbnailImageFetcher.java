package com.captureu.thumbnail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * 썸네일 이미지를 받는다. 주소는 인스타그램 API가 준 값이지만, 서버가 엉뚱한 곳(내부망 등)을 부르지 않게
 * https와 인스타그램·페이스북 CDN 주소만 허용한다.
 */
class ThumbnailImageFetcher {

	static final int MAX_BYTES = 5 * 1024 * 1024;

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	Image fetch(String url) {
		URI uri = allowed(url);
		try {
			HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
					HttpResponse.BodyHandlers.ofByteArray());
			if (res.statusCode() != 200) {
				throw new ThumbnailFetchException("HTTP " + res.statusCode());
			}
			byte[] body = res.body();
			if (body.length == 0 || body.length > MAX_BYTES) {
				throw new ThumbnailFetchException("크기 " + body.length);
			}
			String type = res.headers().firstValue("Content-Type").orElse("image/jpeg").toLowerCase(Locale.ROOT);
			return new Image(body, mediaType(type));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ThumbnailFetchException("중단됨");
		}
		catch (java.io.IOException e) {
			throw new ThumbnailFetchException(e.getClass().getSimpleName());
		}
	}

	static URI allowed(String url) {
		URI uri;
		try {
			uri = URI.create(url);
		}
		catch (IllegalArgumentException e) {
			throw new ThumbnailFetchException("주소 형식 오류");
		}
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		boolean cdn = host.endsWith(".cdninstagram.com") || host.endsWith(".fbcdn.net");
		if (!"https".equals(uri.getScheme()) || !cdn || uri.getPort() != -1) {
			throw new ThumbnailFetchException("허용하지 않는 주소");
		}
		return uri;
	}

	private static String mediaType(String contentType) {
		if (contentType.startsWith("image/png")) {
			return "image/png";
		}
		if (contentType.startsWith("image/webp")) {
			return "image/webp";
		}
		return "image/jpeg";
	}

	record Image(byte[] bytes, String mediaType) {
	}

	static class ThumbnailFetchException extends RuntimeException {

		ThumbnailFetchException(String message) {
			super(message);
		}
	}
}
