package com.captureu.domain.tracking.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.captureu.domain.tracking.entity.Tracking;

public interface TrackingRepository extends JpaRepository<Tracking, String> {

	List<Tracking> findByEnabledTrue();
}
