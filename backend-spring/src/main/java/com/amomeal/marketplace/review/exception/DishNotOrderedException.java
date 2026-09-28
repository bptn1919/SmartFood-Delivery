package com.amomeal.marketplace.review.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/reviews.py::DishNotOrderedException.
 *
 * <p><b>PORT-NOTE — dead code in Django, ported for completeness only.</b>
 * Grepped the whole Django tree: this class is imported by
 * {@code review/services/__init__.py} but never actually raised there —
 * {@code ReviewService.create_review} instead calls
 * {@code order_service.check_dish_in_order(order, dish)}, whose real ORM
 * implementation raises {@code exceptions.dishes.DishNotFoundInOrderException}
 * (a DIFFERENT, correctly-coded 404 class — see
 * {@link com.amomeal.marketplace.dish.exception.DishNotFoundInOrderException}).
 * The only actual caller of {@code DishNotOrderedException} in the whole repo
 * is {@code recommendation/services/__init__.py}, a module not ported yet.
 * Ported here (§4: one Java class per Django exception file entry) but never
 * thrown by {@link com.amomeal.marketplace.review.service.ReviewService}.
 *
 * <p>Also affected by the file-wide {@code status_code}-vs-{@code error_code}
 * quirk (see {@link ReviewNotFoundException}'s javadoc) — real Django status
 * would be 500 regardless.
 */
public class DishNotOrderedException extends ApiException {
    public DishNotOrderedException() {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "DISH_NOT_ORDERED", "Dish was not ordered, cannot review");
    }
}
