package com.captureu.instagram.account;

import java.time.Instant;

/**
 * 게시물 하나. 피드(캐러셀, 이미지)는 thumbnail_url이 없고 media_url이 첫 장 이미지다.
 */
public record MediaItem(String id, String mediaType, String productType, Instant postedAt, String caption,
		String thumbnailUrl, String mediaUrl, String permalink, Integer likeCount, Integer commentsCount) {

	public boolean isReels() {
		return "REELS".equals(productType);
	}

	/** 썸네일로 쓸 이미지 URL. 릴스는 thumbnail_url, 영상이 아닌 게시물은 media_url. */
	public String coverImageUrl() {
		if (thumbnailUrl != null) {
			return thumbnailUrl;
		}
		return "VIDEO".equals(mediaType) ? null : mediaUrl;
	}
}
