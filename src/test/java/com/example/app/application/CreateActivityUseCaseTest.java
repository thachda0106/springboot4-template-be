package com.example.app.application;

import com.example.app.activity.application.usecase.CreateActivityUseCase;
import com.example.app.activity.domain.event.ActivityCreated;
import com.example.app.activity.domain.exception.CreatorNotFoundException;
import com.example.app.activity.domain.model.Activity;
import com.example.app.activity.domain.repository.ActivityRepository;
import com.example.app.security.CurrentUser;
import com.example.app.user.UserLookup;
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

/**
 * Application-layer tests with mocked collaborators - no Spring context.
 * Verifies use-case behavior including event publication.
 */
class CreateActivityUseCaseTest {

    private final ActivityRepository activityRepository = mock(ActivityRepository.class);
    private final UserLookup userLookup = mock(UserLookup.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final CreateActivityUseCase useCase =
            new CreateActivityUseCase(activityRepository, userLookup, eventPublisher);

    private final CurrentUser actor = CurrentUser.of("user-1");

    @Test
    void createsActivityAndPublishesActivityCreated() {
        when(userLookup.findById("user-1")).thenReturn(Optional.of(new UserLookup.Summary("user-1", "Alice")));
        when(activityRepository.save(any(Activity.class))).thenAnswer(inv -> inv.getArgument(0));

        Activity created = useCase.execute("Retro", "Monthly retro", actor);

        assertThat(created.id()).isNotNull();
        assertThat(created.createdBy()).isEqualTo("user-1");

        ArgumentCaptor<ActivityCreated> captor = ArgumentCaptor.forClass(ActivityCreated.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().activityId()).isEqualTo(created.id().value());
        assertThat(captor.getValue().name()).isEqualTo("Retro");
    }

    @Test
    void rejectsUnknownCreatorWithoutPublishing() {
        when(userLookup.findById("user-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute("Retro", null, actor))
                .isInstanceOf(CreatorNotFoundException.class);

        verify(activityRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
