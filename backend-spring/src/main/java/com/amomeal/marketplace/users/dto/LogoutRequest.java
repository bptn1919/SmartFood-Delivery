package com.amomeal.marketplace.users.dto;

/**
 * Mirrors {@code ../backend/users/schemas.py::LogoutRequest} — the refresh
 * token is OPTIONAL, and the whole body is optional. Django's
 * {@code Query.logout} revokes just that one token when it is supplied, and
 * every token the user holds when it is not (FE-admin's
 * {@code authService.logout} sends no body at all).
 */
public record LogoutRequest(String refreshToken) {
}
