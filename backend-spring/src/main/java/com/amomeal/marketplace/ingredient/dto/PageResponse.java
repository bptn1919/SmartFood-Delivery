package com.amomeal.marketplace.ingredient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Mirrors ../../backend/utils/router/paginate.py::PaginatedResponseSchema —
 * the {@code @paginate} decorator's response envelope
 * ({@code content}/{@code current_page}/{@code page_size}/{@code total_rows}/
 * {@code total_pages}), 1-based page numbers matching Django's
 * {@code django.core.paginator.Paginator}. Kept local to `ingredient` for now
 * (first module needing pagination) rather than added to `common`, which this
 * port is not touching per the task's module boundaries; a later module can
 * promote it to a shared DTO.
 */
public record PageResponse<T>(
        List<T> content,
        @JsonProperty("current_page") int currentPage,
        @JsonProperty("page_size") int pageSize,
        @JsonProperty("total_rows") long totalRows,
        @JsonProperty("total_pages") int totalPages
) {
    public static <T> PageResponse<T> of(List<T> content, int requestedPage, int pageSize, long totalRows) {
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) totalRows / pageSize) : 0;
        return new PageResponse<>(content, requestedPage, pageSize, totalRows, totalPages);
    }
}
