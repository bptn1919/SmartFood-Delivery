package com.amomeal.marketplace.ingredient.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/ingredient.py::IngredientImportFileRequired. */
public class IngredientImportFileRequiredException extends ApiException {
    public IngredientImportFileRequiredException() {
        super(HttpStatus.BAD_REQUEST, "INGREDIENT_IMPORT_FILE_REQUIRED", "Excel file is required");
    }
}
