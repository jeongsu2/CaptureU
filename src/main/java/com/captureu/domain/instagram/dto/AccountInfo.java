package com.captureu.domain.instagram.dto;

/**
 * @param reportedMediaCount 계정 정보의 media_count. 실제로 받을 수 있는 게시물 수와 다를 수 있다
 */
public record AccountInfo(String igUserId, String username, Integer followersCount, Integer reportedMediaCount) {
}
