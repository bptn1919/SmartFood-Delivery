package com.amomeal.marketplace.profile.dto;

/** Mirrors the inline {@code CheckChefResponse} schema declared in both `profile`'s and `users`' api.py. */
public record CheckChefResponse(boolean isChef, Integer chefId) {
}
