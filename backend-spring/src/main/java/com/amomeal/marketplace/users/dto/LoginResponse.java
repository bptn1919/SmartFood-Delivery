package com.amomeal.marketplace.users.dto;

/** Mirrors {@code ../backend/users/schemas.py::LoginResponseSchema}. */
public record LoginResponse(String accessToken, String refreshToken, UserResponse user) {
}
