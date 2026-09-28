package com.amomeal.marketplace.review.dto;

import java.time.LocalDate;

/** Mirrors ../../backend/review/schemas/responses.py::IssueTrendPointSchema. */
public record IssueTrendPoint(LocalDate date, int count) {
}
