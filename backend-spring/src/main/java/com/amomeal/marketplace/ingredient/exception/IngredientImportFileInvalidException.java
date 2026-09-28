package com.amomeal.marketplace.ingredient.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/ingredient.py::IngredientImportFileInvalid. */
public class IngredientImportFileInvalidException extends ApiException {
    public IngredientImportFileInvalidException() {
        super(HttpStatus.BAD_REQUEST, "INGREDIENT_IMPORT_FILE_INVALID", "Invalid Excel file format");
    }
}
