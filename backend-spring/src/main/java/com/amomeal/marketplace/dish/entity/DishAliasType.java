package com.amomeal.marketplace.dish.entity;

/**
 * Mirrors the inline {@code choices=} list on
 * ../../backend/dish/models.py::DishAlias.alias_type (no named Django enum).
 */
public enum DishAliasType {
    REGIONAL,
    ABBREVIATION,
    TRANSLATION,
    SYNONYM
}
