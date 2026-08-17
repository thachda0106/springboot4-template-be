package com.example.app.integration;

import com.example.app.activity.domain.event.ActivityCreated;
import com.example.app.activity.domain.model.Activity;
import com.example.app.activity.domain.repository.ActivityRepository;
import com.example.app.workflow.domain.model.WorkflowEntry;
import com.example.app.workflow.domain.model.WorkflowEntryStatus;
import com.example.app.workflow.domain.repository.WorkflowEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module-interaction tests: the workflow module reacts to activity lifecycle
 * events, plus explicit verification of the Spring Modulith event semantics
 * (after-commit delivery, rollback behavior).
 */
class WorkflowEventIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    private WorkflowEntryRepository workflowEntryRepository;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void creatingAnActivityCreatesAWorkflowEntry() throws Exception {
        String userId = createUser("workflow-alice");
        String activityId = createActivity(userId, "Retro");

        mockMvc.perform(get("/api/v1/workflow-entries/{activityId}", activityId)
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityName").value("Retro"))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void updatingAnActivitySynchronizesTheWorkflowEntry() throws Exception {
        String userId = createUser("workflow-bob");
        String activityId = createActivity(userId, "Retro");

        mockMvc.perform(put("/api/v1/activities/{id}", activityId)
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER")).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Retro Q3", "version": 0}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/workflow-entries/{activityId}", activityId)
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityName").value("Retro Q3"))
                .andExpect(jsonPath("$.status").value("UPDATED"));
    }

    @Test
    void deletingAnActivityRemovesItsWorkflowEntry() throws Exception {
        String userId = createUser("workflow-carol");
        String activityId = createActivity(userId, "Retro");

        mockMvc.perform(delete("/api/v1/activities/{id}", activityId)
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "ADMIN")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/workflow-entries/{activityId}", activityId)
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKFLOW_ENTRY_NOT_FOUND"));
    }

    @Test
    void eventPublishedInCommittedTransactionIsDeliveredAfterCommit() throws Exception {
        // Create a real activity row (via the repository, so no event is published
        // and no listener runs - the workflow entry can only come from our manual publish).
        UUID activityId = createActivityRow();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> eventPublisher.publishEvent(new ActivityCreated(activityId, "Retro")));

        Optional<WorkflowEntry> entry = workflowEntryRepository.findByActivityId(activityId);
        assertThat(entry).isPresent();
        assertThat(entry.get().status()).isEqualTo(WorkflowEntryStatus.CREATED);
    }

    @Test
    void eventPublishedInRolledBackTransactionIsNotDelivered() throws Exception {
        UUID activityId = createActivityRow();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new ActivityCreated(activityId, "Retro"));
            throw new IllegalStateException("forcing rollback");
        })).isInstanceOf(IllegalStateException.class);

        // AFTER_COMMIT semantics: the listener must NOT have run.
        assertThat(workflowEntryRepository.findByActivityId(activityId)).isEmpty();
    }

    @Test
    void duplicateDeliveryOfCreatedEventIsIdempotent() throws Exception {
        UUID activityId = createActivityRow();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> eventPublisher.publishEvent(new ActivityCreated(activityId, "Retro")));
        tx.executeWithoutResult(status -> eventPublisher.publishEvent(new ActivityCreated(activityId, "Retro")));

        // One entry only, regardless of how often the event is delivered.
        assertThat(workflowEntryRepository.findByActivityId(activityId)).isPresent();
    }

    /** Inserts an activity row directly (no event published), returning its id. */
    private UUID createActivityRow() throws Exception {
        String creatorId = createUser("workflow-" + UUID.randomUUID().toString().substring(0, 8));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> activityRepository.save(
                Activity.create("Retro", null, creatorId))).id().value();
    }

    private String createActivity(String userId, String name) throws Exception {
        var result = mockMvc.perform(post("/api/v1/activities")
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER")).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}
