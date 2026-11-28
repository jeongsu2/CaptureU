package com.captureu.domain.thumbnail.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.captureu.domain.thumbnail.entity.MediaThumbnail;

public interface MediaThumbnailRepository extends JpaRepository<MediaThumbnail, String> {
}
