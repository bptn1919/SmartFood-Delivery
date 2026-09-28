package com.amomeal.marketplace.order.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Post-port suspension enforcement (2026-09-25). A chef with {@code is_accepting_orders=false}
 * or a FULL_LOCK suspension cannot receive new orders. Django never enforced this.
 */
public class ChefSuspendedException extends ApiException {

    public ChefSuspendedException() {
        super(HttpStatus.BAD_REQUEST, "CHEF_SUSPENDED", "This chef is currently not accepting orders");
    }
}
