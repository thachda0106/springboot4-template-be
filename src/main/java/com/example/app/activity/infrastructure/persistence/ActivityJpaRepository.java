package com.example.app.activity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data repository - internal persistence detail of the activity module.
 * Only {@link JpaActivityRepository} may use it.
 */
interface ActivityJpaRepository extends JpaRepository<ActivityJpaEntity, UUID> {
}
