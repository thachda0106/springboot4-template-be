package com.example.app.user.api;

import com.example.app.security.CurrentUserProvider;
import com.example.app.user.api.dto.AuthResponse;
import com.example.app.user.api.dto.LoginRequest;
import com.example.app.user.api.dto.RefreshTokenRequest;
import com.example.app.user.application.LoginUseCase;
import com.example.app.user.application.LogoutUseCase;
import com.example.app.user.application.RefreshTokenUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints: login (email + password → token pair), refresh (rotation)
 * and logout (revocation). Contains no business logic; the user module now owns
 * first-party authentication (see docs/security.md).
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final LoginUseCase loginUseCase;
    private final RefreshTokenUseCase refreshTokenUseCase;
    private final LogoutUseCase logoutUseCase;
    private final CurrentUserProvider currentUserProvider;

    public AuthController(LoginUseCase loginUseCase, RefreshTokenUseCase refreshTokenUseCase,
                          LogoutUseCase logoutUseCase, CurrentUserProvider currentUserProvider) {
        this.loginUseCase = loginUseCase;
        this.refreshTokenUseCase = refreshTokenUseCase;
        this.logoutUseCase = logoutUseCase;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return AuthResponse.from(loginUseCase.execute(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return AuthResponse.from(refreshTokenUseCase.execute(request.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        logoutUseCase.execute(request.refreshToken(), currentUserProvider.currentUser().id());
    }
}