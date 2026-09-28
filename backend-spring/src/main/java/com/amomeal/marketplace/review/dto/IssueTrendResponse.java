package com.amomeal.marketplace.review.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Mirrors ../../backend/review/schemas/responses.py::IssueTrendResponse. */
public record IssueTrendResponse(LocalDate rangeStart, LocalDate rangeEnd, Map<String, List<IssueTrendPoint>> issues) {
}
