package com.amomeal.marketplace.profile.entity;

/**
 * Mirrors ../../backend/utils/enums.py::DietModeEnum. Lives on
 * {@code CustomerProfile.diet_mode} — an input the not-yet-ported
 * `recommendation` module will consume later (CLAUDE.md §7). This port only
 * models the field faithfully; no recommendation logic is built here.
 */
public enum DietMode {
    NONE,
    BALANCED,
    LOW_CARB,
    HIGH_PROTEIN,
    LOW_FAT,
    LIGHT
}
