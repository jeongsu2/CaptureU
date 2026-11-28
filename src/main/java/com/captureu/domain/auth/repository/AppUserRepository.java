package com.captureu.domain.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.captureu.domain.auth.entity.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, String> {
}
