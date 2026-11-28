package com.captureu.domain.analysis.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.captureu.domain.analysis.entity.IgMedia;
import com.captureu.domain.analysis.repository.IgMediaRepository;

/** 다른 기능(썸네일 특징 #11 등)이 게시물 정보를 읽을 때 쓰는 조회. */
@Service
@Transactional(readOnly = true)
public class MediaQueryService {

	private final IgMediaRepository mediaRepository;

	MediaQueryService(IgMediaRepository mediaRepository) {
		this.mediaRepository = mediaRepository;
	}

	/** 게시물 ID → 썸네일 URL과 만료 시각. 썸네일이 없는 게시물은 빠진다. */
	public Map<String, ThumbnailRef> thumbnails(Collection<String> mediaIds) {
		return mediaRepository.findAllById(mediaIds)
			.stream()
			.filter(m -> m.getThumbnailUrl() != null)
			.collect(Collectors.toMap(IgMedia::getId,
					m -> new ThumbnailRef(m.getThumbnailUrl(), m.getThumbnailUrlExpiresAt())));
	}

	/** @param expiresAt 알 수 없으면 null */
	public record ThumbnailRef(String url, Instant expiresAt) {

		public boolean expired(Instant now) {
			return expiresAt != null && !expiresAt.isAfter(now);
		}
	}
}
