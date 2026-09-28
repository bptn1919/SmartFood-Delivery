package com.amomeal.marketplace.review.dto;

import java.util.UUID;

/** Mirrors ../../backend/review/schemas/responses.py::ChefDishIssueReportSchema. */
public record ChefDishIssueReport(UUID dishUid, String dishName, DishIssueWindow thisWeek, DishIssueWindow lastWeek) {
}
