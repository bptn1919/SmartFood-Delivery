package com.amomeal.marketplace.dish.entity;

/**
 * Mirrors ../../backend/utils/enums.py::AllergyModeEnum. Lives on
 * {@code profile.CustomerProfile} in Django; that module isn't ported yet, so
 * {@link com.amomeal.marketplace.dish.service.DishUserContext} resolves it to
 * {@link #WARN} (Django's own "no profile row" default) for now.
 */
public enum AllergyMode {
    WARN,
    HIDE
}
