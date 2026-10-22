package com.dastakhat.users;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** The models of the authentication API. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) { }

    public record UserView(java.util.UUID id, String email, String name, String role) { }

    public record LoginResponse(UserView user, String csrfToken) { }
}
