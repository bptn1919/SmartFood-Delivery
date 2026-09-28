package com.amomeal.marketplace.dish.exception;

/**
 * Mirrors the plain {@code ValueError}s raised by
 * ../../backend/dish/services/stock_reservation.py and
 * ../../backend/dish/orm/dish.py's {@code reduce_quantity}.
 *
 * <p><b>Deliberately NOT an {@link com.amomeal.marketplace.common.exception.ApiException}.</b>
 * Django raises a bare {@code ValueError} here, which
 * {@code utils/router/exception.py} does not special-case — it falls through to
 * the generic 500 {@code CONTACT_ADMIN_FOR_SUPPORT} handler. Extending
 * {@code ApiException} would change that contract (a 4xx with a business
 * message_code), so per CLAUDE.md §0.1 this port keeps the plain
 * {@code RuntimeException} shape and lets
 * {@code common.exception.GlobalExceptionHandler} produce the same 500 the
 * Django stack produces today. Callers in `order`/`payment` catch it explicitly
 * the same way they would catch Django's {@code ValueError}.
 *
 * <p>The Vietnamese message strings are reproduced verbatim (CLAUDE.md §4 —
 * user-facing copy is not translated).
 */
public class StockReservationException extends RuntimeException {

    public StockReservationException(String message) {
        super(message);
    }

    /** Django: {@code ValueError(f"Dish {dish.name} chưa có sẵn trong ngày {available_date}")}. */
    public static StockReservationException noAvailabilityRecord(String dishName, Object availableDate) {
        return new StockReservationException("Dish " + dishName + " chưa có sẵn trong ngày " + availableDate);
    }

    /** Django: {@code ValueError(f"Không đủ số lượng món {dish.name} trong ngày {available_date}")}. */
    public static StockReservationException insufficientStock(String dishName, Object availableDate) {
        return new StockReservationException("Không đủ số lượng món " + dishName + " trong ngày " + availableDate);
    }
}
