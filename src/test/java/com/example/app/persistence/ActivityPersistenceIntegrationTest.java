package com.example.app.persistence;

import com.example.app.activity.domain.model.Activity;
import com.example.app.activity.domain.model.ActivityId;
import com.example.app.activity.domain.repository.ActivityRepository;
import com.example.app.integration.AbstractIntegrationTest;
import com.example.app.user.domain.model.User;
import com.example.app.user.domain.model.UserRole;
import com.example.app.user.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence integration tests through the DOMAIN repository contract
 * (infrastructure implementation + real PostgreSQL + Flyway schema).
 */
class ActivityPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    private String creatorId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        // created_by is a FK to users(id) - create a real user first.
        creatorId = tx.execute(status -> userRepository.save(User.create(
                "Persistence Tester", "persistence-" + UUID.randomUUID() + "@example.com",
                "hash", UserRole.USER))).id().value().toString();
    }

    @Test
    void saveAndFindByIdRoundTrip() {
        Activity created = tx.execute(status -> activityRepository.save(
                Activity.create("Retro", "desc", creatorId)));

        assertThat(created.id()).isNotNull();
        assertThat(created.version()).isZero();
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.updatedAt()).isNotNull();

        Optional<Activity> found = tx.execute(status -> activityRepository.findById(created.id()));

        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("Retro");
        assertThat(found.get().createdBy()).isEqualTo(creatorId);
        assertThat(found.get().version()).isZero();
    }

    @Test
    void findUnknownIdReturnsEmpty() {
        Optional<Activity> found = tx.execute(status -> activityRepository.findById(ActivityId.random()));

        assertThat(found).isEmpty();
    }

    @Test
    void concurrentUpdateWithStaleVersionFails() {
        // tx1: create (version 0)
        Activity created = tx.execute(status -> activityRepository.save(
                Activity.create("Retro", null, creatorId)));

        // tx2: read a stale copy (version 0), then commit
        Activity stale = tx.execute(status -> activityRepository.findById(created.id()).orElseThrow());

        // tx3: read fresh, update, save -> version 1
        tx.executeWithoutResult(status -> {
            Activity fresh = activityRepository.findById(created.id()).orElseThrow();
            fresh.update("Retro Q3", null);
            activityRepository.save(fresh);
        });

        // tx4: updating the stale copy (version 0) must fail with a lock conflict
        stale.update("Retro Q4", null);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> activityRepository.save(stale)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void deleteRemovesTheRow() {
        Activity created = tx.execute(status -> activityRepository.save(
                Activity.create("Retro", null, creatorId)));

        tx.executeWithoutResult(status -> activityRepository.delete(created.id()));

        Optional<Activity> found = tx.execute(status -> activityRepository.findById(created.id()));
        assertThat(found).isEmpty();
    }
}
