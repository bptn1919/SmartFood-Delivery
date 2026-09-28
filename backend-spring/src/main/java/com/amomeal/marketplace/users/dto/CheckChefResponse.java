package com.amomeal.marketplace.users.dto;

/**
 * Mirrors the inline {@code AuthenticateAPI.CheckChefResponse} schema in
 * {@code ../backend/users/api.py}. See {@code AuthService.checkIsChef} for why
 * {@code chef_id} is always null.
 */
public record CheckChefResponse(boolean isChef, Long chefId) {
}
