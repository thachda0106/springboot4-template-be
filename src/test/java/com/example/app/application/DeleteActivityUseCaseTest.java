package com.example.app.application;

import com.example.app.activity.application.usecase.DeleteActivityUseCase;
import com.example.app.activity.domain.event.ActivityDeleted;
import com.example.app.activity.domain.exception.ActivityNotFoundException;
import com.example.app.activity.domain.model.Activity;
import com.example.app.activity.domain.model.ActivityId;
import com.example.app.activity.domain.repository.ActivityRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeleteActivityUseCaseTest {

    private final ActivityRepository activityRepository = mock(ActivityRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final DeleteActivityUseCase useCase =
            new DeleteActivityUseCase(activityRepository, eventPublisher, meterRegistry);

    private double lifecycle(String action) {
        Counter counter = meterRegistry.find("app.activities.lifecycle").tag("action", action).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void deletesActivityAndPublishesActivityDeleted() {
        Activity existing = Activity.restore(ActivityId.random(), "Retro", null,
                com.example.app.activity.domain.model.ActivityStatus.DRAFT, "user-1", 0L, null, null);
        when(activityRepository.findById(existing.id())).thenReturn(Optional.of(existing));

        useCase.execute(existing.id());

        verify(activityRepository).delete(existing.id());
        ArgumentCaptor<ActivityDeleted> captor = ArgumentCaptor.forClass(ActivityDeleted.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().activityId()).isEqualTo(existing.id().value());

        assertThat(lifecycle("deleted")).isEqualTo(1);
    }

    @Test
    void rejectsUnknownActivity() {
        ActivityId id = ActivityId.random();
        when(activityRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(id))
                .isInstanceOf(ActivityNotFoundException.class);

        verify(activityRepository, never()).delete(any());
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(lifecycle("deleted")).isZero();
    }
}
