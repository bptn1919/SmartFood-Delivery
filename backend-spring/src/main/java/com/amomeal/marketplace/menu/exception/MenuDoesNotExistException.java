package com.amomeal.marketplace.menu.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/menus.py::MenuDoesNotExist. */
public class MenuDoesNotExistException extends ApiException {

    public MenuDoesNotExistException() {
        super(HttpStatus.NOT_FOUND, "MENU_DOES_NOT_EXIST", "Menu does not exist");
    }
}
