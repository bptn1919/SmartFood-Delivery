package com.amomeal.marketplace.profile.dto;

/** Mirrors the bare {@code {"message": "..."}} dict Django's set_default_address returns (no declared response schema). */
public record SetDefaultAddressResponse(String message) {
}
