package com.amomeal.marketplace.dish.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/dishes.py::DishIsNotDeleted.
 *
 * <h2>PORT-NOTE — the message text below is a faithfully-preserved Django bug</h2>
 * Django's class body is:
 * <pre>
 * class DishIsNotDeleted(APIException):
 *     error_code = HTTPStatus.BAD_REQUEST
 *     message_code = "DISH_NOT_DELETED"
 *
 *
 * class DishPermissionDenied(APIException):
 *     error_code = HTTPStatus.FORBIDDEN
 *     message_code = "DISH_PERMISSION_DENIED"
 *     message = "You can only modify your own dishes"
 *     message = "Dish is not deleted"
 * </pre>
 * i.e. the author clearly meant {@code message = "Dish is not deleted"} to sit on
 * <b>this</b> class, but it was pasted into {@code DishPermissionDenied}'s body,
 * where it silently shadows that class's real message. The net effect in Django
 * today:
 * <ul>
 *   <li>{@code DishIsNotDeleted.message} falls back to the {@code APIException}
 *       base default, {@code "Internal server error"} (the status/error_code is
 *       still 400 — only the human-readable {@code message} is wrong);</li>
 *   <li>{@code DishPermissionDenied.message} is {@code "Dish is not deleted"}
 *       instead of {@code "You can only modify your own dishes"}.</li>
 * </ul>
 * Per CLAUDE.md §0.1 this port replicates both strings exactly rather than
 * quietly fixing them — the FE may already be keyed on them. See also
 * {@link DishPermissionDeniedException}.
 */
public class DishIsNotDeletedException extends ApiException {

    public DishIsNotDeletedException() {
        // "Internal server error" = APIException's base-class default that Django
        // inherits here; NOT a copy/paste slip in this port. See class javadoc.
        super(HttpStatus.BAD_REQUEST, "DISH_NOT_DELETED", "Internal server error");
    }
}
