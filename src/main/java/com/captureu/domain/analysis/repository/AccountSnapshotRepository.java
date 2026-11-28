package com.captureu.domain.analysis.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.captureu.domain.analysis.entity.AccountSnapshot;

public interface AccountSnapshotRepository extends JpaRepository<AccountSnapshot, Long> {
}
