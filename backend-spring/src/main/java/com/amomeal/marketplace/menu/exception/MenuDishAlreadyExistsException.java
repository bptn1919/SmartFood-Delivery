package com.amomeal.marketplace.menu.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/menus.py::MenuDishAlreadyExists. */
public class MenuDishAlreadyExistsException extends ApiException {

    public MenuDishAlreadyExistsException() {
        super(HttpStatus.CONFLICT, "MENU_DISH_ALREADY_EXISTS", "This dish is already in the menu");
    }
}
