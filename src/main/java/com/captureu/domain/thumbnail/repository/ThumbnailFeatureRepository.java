package com.captureu.domain.thumbnail.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.captureu.domain.thumbnail.entity.ThumbnailFeature;

public interface ThumbnailFeatureRepository extends JpaRepository<ThumbnailFeature, String> {
}
