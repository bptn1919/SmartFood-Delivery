package com.amomeal.marketplace.ingredient.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/ingredient.py::IngredientAliasNotFound.
 * PORT-NOTE: unused (dead code) in Django's current code today too — no call
 * site raises it in ../../backend (grepped). Ported anyway since it's part of
 * the module's declared exception surface (CLAUDE.md §4 — one Java class per
 * Django exception in exceptions/&lt;module&gt;.py), for future alias-lookup
 * endpoints to reuse.
 */
public class IngredientAliasNotFoundException extends ApiException {
    public IngredientAliasNotFoundException() {
        super(HttpStatus.NOT_FOUND, "INGREDIENT_ALIAS_NOT_FOUND", "Ingredient alias not found");
    }
}
