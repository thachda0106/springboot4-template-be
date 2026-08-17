package com.example.app.activity.api;

import com.example.app.activity.api.dto.ActivityResponse;
import com.example.app.activity.api.dto.CreateActivityRequest;
import com.example.app.activity.api.dto.UpdateActivityRequest;
import com.example.app.activity.application.create.CreateActivityUseCase;
import com.example.app.activity.application.delete.DeleteActivityUseCase;
import com.example.app.activity.application.get.GetActivityQuery;
import com.example.app.activity.application.update.UpdateActivityUseCase;
import com.example.app.activity.domain.model.Activity;
import com.example.app.activity.domain.model.ActivityId;
import com.example.app.security.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * REST API of the activity module. Contains no business logic: it validates
 * DTOs, maps parameters, delegates to use cases and assembles responses.
 * Authorization is enforced declaratively by SecurityConfig (scope-based).
 */
@RestController
@RequestMapping("/activities")
public class ActivityController {

    private final CreateActivityUseCase createActivityUseCase;
    private final UpdateActivityUseCase updateActivityUseCase;
    private final DeleteActivityUseCase deleteActivityUseCase;
    private final GetActivityQuery getActivityQuery;
    private final CurrentUserProvider currentUserProvider;

    public ActivityController(CreateActivityUseCase createActivityUseCase,
                              UpdateActivityUseCase updateActivityUseCase,
                              DeleteActivityUseCase deleteActivityUseCase,
                              GetActivityQuery getActivityQuery,
                              CurrentUserProvider currentUserProvider) {
        this.createActivityUseCase = createActivityUseCase;
        this.updateActivityUseCase = updateActivityUseCase;
        this.deleteActivityUseCase = deleteActivityUseCase;
        this.getActivityQuery = getActivityQuery;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public ResponseEntity<ActivityResponse> create(@Valid @RequestBody CreateActivityRequest request) {
        Activity activity = createActivityUseCase.execute(
                request.name(), request.description(), currentUserProvider.currentUser());

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(activity.id().value())
                .toUri();
        return ResponseEntity.created(location).body(ActivityResponse.from(activity));
    }

    @GetMapping("/{id}")
    public ActivityResponse get(@PathVariable UUID id) {
        return ActivityResponse.from(getActivityQuery.findById(ActivityId.from(id)));
    }

    @PutMapping("/{id}")
    public ActivityResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateActivityRequest request) {
        Activity activity = updateActivityUseCase.execute(
                ActivityId.from(id), request.name(), request.description(), request.version());
        return ActivityResponse.from(activity);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        deleteActivityUseCase.execute(ActivityId.from(id));
    }
}
