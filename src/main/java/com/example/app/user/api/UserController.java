package com.example.app.user.api;

import com.example.app.security.CurrentUserProvider;
import com.example.app.user.api.dto.CreateUserRequest;
import com.example.app.user.api.dto.UserResponse;
import com.example.app.user.application.CreateUserUseCase;
import com.example.app.user.application.UserLookupService;
import com.example.app.user.domain.model.User;
import com.example.app.user.domain.model.UserId;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * REST API of the user module. Contains no business logic; the user module is a
 * business module, not an Identity Provider (no credential endpoints here).
 */
@RestController
@RequestMapping("/users")
public class UserController {

    private final CreateUserUseCase createUserUseCase;
    private final UserLookupService userLookupService;
    private final CurrentUserProvider currentUserProvider;

    public UserController(CreateUserUseCase createUserUseCase, UserLookupService userLookupService,
                          CurrentUserProvider currentUserProvider) {
        this.createUserUseCase = createUserUseCase;
        this.userLookupService = userLookupService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        User user = createUserUseCase.execute(request.name(), request.email(), request.password(), request.role());

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(user.id().value())
                .toUri();
        return ResponseEntity.created(location).body(UserResponse.from(user));
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable UUID id) {
        return UserResponse.from(userLookupService.getById(UserId.from(id)));
    }

    @GetMapping("/me")
    public UserResponse me() {
        String currentUserId = currentUserProvider.currentUser().id();
        return UserResponse.from(userLookupService.getById(UserId.from(UUID.fromString(currentUserId))));
    }
}
