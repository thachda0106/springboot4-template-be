package com.example.app.unit;

import com.example.app.activity.domain.exception.InvalidActivityException;
import com.example.app.activity.domain.model.Activity;
import com.example.app.activity.domain.model.ActivityStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure domain unit tests - no Spring context.
 */
class ActivityTest {

    @Test
    void createSetsDraftStatusAndTrimsName() {
        Activity activity = Activity.create("  Team retro  ", "Monthly retro", "user-1");

        assertThat(activity.name()).isEqualTo("Team retro");
        assertThat(activity.description()).isEqualTo("Monthly retro");
        assertThat(activity.status()).isEqualTo(ActivityStatus.DRAFT);
        assertThat(activity.createdBy()).isEqualTo("user-1");
        assertThat(activity.id()).isNotNull();
        assertThat(activity.version()).isNull();
    }

    @Test
    void createRejectsBlankName() {
        assertThatThrownBy(() -> Activity.create("   ", "desc", "user-1"))
                .isInstanceOf(InvalidActivityException.class)
                .hasMessageContaining("name");
    }

    @Test
    void createRejectsBlankCreator() {
        assertThatThrownBy(() -> Activity.create("Retro", "desc", " "))
                .isInstanceOf(InvalidActivityException.class)
                .hasMessageContaining("creator");
    }

    @Test
    void updateActivatesActivityAndTrimsName() {
        Activity activity = Activity.create("Retro", "desc", "user-1");

        activity.update("  Retro Q3  ", "New description");

        assertThat(activity.name()).isEqualTo("Retro Q3");
        assertThat(activity.description()).isEqualTo("New description");
        assertThat(activity.status()).isEqualTo(ActivityStatus.ACTIVE);
    }

    @Test
    void updateRejectsBlankName() {
        Activity activity = Activity.create("Retro", "desc", "user-1");

        assertThatThrownBy(() -> activity.update(null, "desc"))
                .isInstanceOf(InvalidActivityException.class);
    }
}
