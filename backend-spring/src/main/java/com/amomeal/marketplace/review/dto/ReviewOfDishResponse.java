package com.amomeal.marketplace.review.dto;

import java.util.List;

/** Mirrors ../../backend/review/schemas/responses.py::ReviewOfDishRespone (Django's own typo kept out of the Java name). */
public record ReviewOfDishResponse(ReviewDishInfo dishInfo, List<ReviewDetailResponse> reviews) {
}
