package com.amomeal.marketplace.review.dto;

import java.util.UUID;

/** Mirrors ../../backend/review/schemas/responses.py::TopComplainedDishSchema. */
public record TopComplainedDish(UUID dishUid, String dishName, int count) {
}
