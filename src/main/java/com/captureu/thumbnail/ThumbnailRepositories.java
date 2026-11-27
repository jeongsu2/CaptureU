package com.captureu.thumbnail;

import org.springframework.data.jpa.repository.JpaRepository;

interface ThumbnailFeatureRepository extends JpaRepository<ThumbnailFeature, String> {
}

interface MediaThumbnailRepository extends JpaRepository<MediaThumbnail, String> {
}
