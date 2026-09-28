package com.amomeal.marketplace.menu.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/menus.py::MenuIsNotDeleted. */
public class MenuIsNotDeletedException extends ApiException {

    public MenuIsNotDeletedException() {
        super(HttpStatus.FORBIDDEN, "MENU_IS_NOT_DELETED", "Menu is not deleted");
    }
}
