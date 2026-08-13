package com.example.app.unit;

import com.example.app.workflow.domain.model.WorkflowEntry;
import com.example.app.workflow.domain.model.WorkflowEntryStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure domain unit tests for the workflow module's stub model.
 */
class WorkflowEntryTest {

    @Test
    void entryForActivityStartsInCreatedStatus() {
        UUID activityId = UUID.randomUUID();

        WorkflowEntry entry = WorkflowEntry.forActivity(activityId, "Retro");

        assertThat(entry.activityId()).isEqualTo(activityId);
        assertThat(entry.activityName()).isEqualTo("Retro");
        assertThat(entry.status()).isEqualTo(WorkflowEntryStatus.CREATED);
        assertThat(entry.id()).isNotNull();
    }

    @Test
    void syncFromActivityUpdatesNameAndStatus() {
        WorkflowEntry entry = WorkflowEntry.forActivity(UUID.randomUUID(), "Retro");

        entry.syncFromActivity("Retro Q3");

        assertThat(entry.activityName()).isEqualTo("Retro Q3");
        assertThat(entry.status()).isEqualTo(WorkflowEntryStatus.UPDATED);
    }
}
