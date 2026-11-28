package com.captureu.domain.thumbnail.client;

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
public class ThumbnailImageFetcher {

	static final int MAX_BYTES = 5 * 1024 * 1024;

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	public Image fetch(String url) {
		URI uri = allowed(url);
		try {
			HttpResponse<java.io.InputStream> res = http.send(
					HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
					HttpResponse.BodyHandlers.ofInputStream());
			try (java.io.InputStream in = res.body()) {
				if (res.statusCode() != 200) {
					throw new ThumbnailFetchException("HTTP " + res.statusCode());
				}
				long declared = res.headers().firstValueAsLong("Content-Length").orElse(-1);
				if (declared > MAX_BYTES) {
					throw new ThumbnailFetchException("크기 " + declared);
				}
				String type = mediaType(res.headers().firstValue("Content-Type").orElse(""));
				// 큰 응답을 통째로 메모리에 올리지 않게 상한 + 1바이트까지만 읽는다
				byte[] body = in.readNBytes(MAX_BYTES + 1);
				if (body.length == 0 || body.length > MAX_BYTES) {
					throw new ThumbnailFetchException("크기 " + body.length);
				}
				return new Image(body, type);
			}
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

	/** Claude가 받는 형식(jpeg, png, webp)만 받는다. 그 밖의 응답은 매번 다시 받아도 실패하므로 여기서 거절한다. */
	static String mediaType(String contentType) {
		String type = contentType.toLowerCase(Locale.ROOT).split(";")[0].strip();
		return switch (type) {
			case "image/jpeg", "image/jpg" -> "image/jpeg";
			case "image/png" -> "image/png";
			case "image/webp" -> "image/webp";
			default -> throw new ThumbnailFetchException("지원하지 않는 형식 " + (type.isEmpty() ? "(없음)" : type));
		};
	}

	public record Image(byte[] bytes, String mediaType) {
	}

	static class ThumbnailFetchException extends RuntimeException {

		ThumbnailFetchException(String message) {
			super(message);
		}
	}
}
