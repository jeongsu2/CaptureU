package com.captureu.domain.tracking.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.captureu.domain.tracking.entity.MediaCheckpoint;

public interface MediaCheckpointRepository extends JpaRepository<MediaCheckpoint, Long> {

	List<MediaCheckpoint> findByMediaIdIn(Collection<String> mediaIds);

	List<MediaCheckpoint> findByIgUserId(String igUserId);
}
