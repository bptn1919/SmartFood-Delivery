package com.amomeal.marketplace.review.dto;

import java.time.LocalDate;
import java.util.List;

/** Mirrors ../../backend/review/schemas/responses.py::TopComplainedDishesResponse. */
public record TopComplainedDishesResponse(LocalDate rangeStart, LocalDate rangeEnd, int limit,
                                           List<TopComplainedDish> items) {
}
