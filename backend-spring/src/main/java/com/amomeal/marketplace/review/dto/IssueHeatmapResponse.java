package com.amomeal.marketplace.review.dto;

import java.time.LocalDate;
import java.util.Map;

/** Mirrors ../../backend/review/schemas/responses.py::IssueHeatmapResponse. */
public record IssueHeatmapResponse(LocalDate rangeStart, LocalDate rangeEnd, Map<String, Map<String, Integer>> heatmap) {
}
