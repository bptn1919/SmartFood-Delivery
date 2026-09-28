package com.amomeal.marketplace.profile.dto;

import java.util.List;

/**
 * Mirrors django-ninja's stock {@code PageNumberPagination} output shape
 * ({@code {"items": [...], "count": N}}) — the built-in ninja paginator
 * {@code GET /api/chef-profiles/} uses, NOT the custom
 * {@code utils/router/paginate.py::Pagination} class `ingredient` ported as
 * {@code PageResponse} (which `profile` does not use — different endpoint,
 * different paginator, confirmed from the Django import in profile/api.py:
 * {@code from ninja.pagination import paginate, PageNumberPagination}).
 */
public record ChefProfilePage(List<ChefProfilePublicResponse> items, long count) {
}
