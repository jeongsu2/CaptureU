package com.captureu.recommendation;

import org.springframework.data.jpa.repository.JpaRepository;

interface AdoptionRejectionRepository extends JpaRepository<AdoptionRejection, AdoptionRejection.Key> {
}
