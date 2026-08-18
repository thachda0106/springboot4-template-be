package com.example.app.application;

import com.example.app.activity.application.usecase.UpdateActivityUseCase;
import com.example.app.activity.domain.event.ActivityUpdated;
import com.example.app.activity.domain.exception.ActivityNotFoundException;
import com.example.app.activity.domain.model.Activity;
import com.example.app.activity.domain.model.ActivityId;
import com.example.app.activity.domain.repository.ActivityRepository;
import com.example.app.shared.ConflictException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
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

class UpdateActivityUseCaseTest {

    private final ActivityRepository activityRepository = mock(ActivityRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final UpdateActivityUseCase useCase =
            new UpdateActivityUseCase(activityRepository, eventPublisher, meterRegistry);

    private Activity existing;

    private double lifecycle(String action) {
        Counter counter = meterRegistry.find("app.activities.lifecycle").tag("action", action).counter();
        return counter == null ? 0 : counter.count();
    }

    @BeforeEach
    void setUp() {
        existing = Activity.restore(ActivityId.random(), "Retro", "desc",
                com.example.app.activity.domain.model.ActivityStatus.DRAFT, "user-1", 0L, null, null);
    }

    @Test
    void updatesActivityAndPublishesActivityUpdated() {
        when(activityRepository.findById(existing.id())).thenReturn(Optional.of(existing));
        when(activityRepository.save(any(Activity.class))).thenAnswer(inv -> inv.getArgument(0));

        Activity updated = useCase.execute(existing.id(), "Retro Q3", "New desc", 0L);

        assertThat(updated.name()).isEqualTo("Retro Q3");
        assertThat(updated.status()).isEqualTo(com.example.app.activity.domain.model.ActivityStatus.ACTIVE);

        ArgumentCaptor<ActivityUpdated> captor = ArgumentCaptor.forClass(ActivityUpdated.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().activityId()).isEqualTo(existing.id().value());
        assertThat(captor.getValue().name()).isEqualTo("Retro Q3");
        assertThat(captor.getValue().status()).isEqualTo("ACTIVE");

        assertThat(lifecycle("updated")).isEqualTo(1);
    }

    @Test
    void rejectsStaleVersionWithConflict() {
        when(activityRepository.findById(existing.id())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> useCase.execute(existing.id(), "Retro Q3", null, 5L))
                .isInstanceOf(ConflictException.class);

        verify(activityRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(lifecycle("updated")).isZero();
    }

    @Test
    void rejectsUnknownActivity() {
        when(activityRepository.findById(existing.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(existing.id(), "Retro Q3", null, 0L))
                .isInstanceOf(ActivityNotFoundException.class);

        assertThat(lifecycle("updated")).isZero();
    }
}
