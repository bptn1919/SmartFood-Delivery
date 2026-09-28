package com.amomeal.marketplace.review.web;

import com.amomeal.marketplace.review.dto.IssueHeatmapResponse;
import com.amomeal.marketplace.review.dto.IssueReviewsResponse;
import com.amomeal.marketplace.review.dto.IssueTrendResponse;
import com.amomeal.marketplace.review.dto.ReviewIssueFilter;
import com.amomeal.marketplace.review.dto.TopComplainedDishesResponse;
import com.amomeal.marketplace.review.service.ReviewAnalyticsService;
import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/review/api.py::ReviewAnalyticsController — all four
 * endpoints are chef-facing (Django: {@code auth=True} explicitly on each,
 * same effective requirement as the controller-level {@code AuthBear()}).
 * See {@link com.amomeal.marketplace.review.service.ReviewAnalyticsService}'s
 * javadoc for the {@code menu_uid}/{@code categories} filter scope reduction.
 */
@RestController
@RequestMapping("/api/reviews/chef")
@RequiredArgsConstructor
public class ReviewAnalyticsController {

    private final ReviewAnalyticsService analyticsService;

    /** Django: {@code GET /api/reviews/chef/analytics/issue-trend}. */
    @GetMapping("/analytics/issue-trend")
    public IssueTrendResponse issueTrend(@AuthenticationPrincipal CustomUser user,
                                          @RequestParam(name = "range_start", required = false)
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate rangeStart,
                                          @RequestParam(name = "range_end", required = false)
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate rangeEnd) {
        return analyticsService.issueTrend(user, rangeStart, rangeEnd);
    }

    /** Django: {@code GET /api/reviews/chef/analytics/top-complained-dishes}. */
    @GetMapping("/analytics/top-complained-dishes")
    public TopComplainedDishesResponse topComplainedDishes(
            @AuthenticationPrincipal CustomUser user,
            @RequestParam(name = "range_start", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate rangeStart,
            @RequestParam(name = "range_end", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate rangeEnd,
            @RequestParam(required = false) Integer limit) {
        return analyticsService.topComplainedDishes(user, rangeStart, rangeEnd, limit);
    }

    /** Django: {@code GET /api/reviews/chef/analytics/issue-heatmap}. */
    @GetMapping("/analytics/issue-heatmap")
    public IssueHeatmapResponse issueHeatmap(@AuthenticationPrincipal CustomUser user,
                                              @RequestParam(name = "range_start", required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate rangeStart,
                                              @RequestParam(name = "range_end", required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate rangeEnd) {
        return analyticsService.issueHeatmap(user, rangeStart, rangeEnd);
    }

    /** Django: {@code GET /api/reviews/chef/issue-reviews}. */
    @GetMapping("/issue-reviews")
    public IssueReviewsResponse chefIssueReviews(
            @AuthenticationPrincipal CustomUser user,
            @RequestParam String issue,
            @RequestParam(name = "dish_uid", required = false) UUID dishUid,
            @RequestParam(name = "menu_uid", required = false) UUID menuUid,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String categories,
            @RequestParam(name = "range_start", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate rangeStart,
            @RequestParam(name = "range_end", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate rangeEnd) {
        List<String> categoryList = categories == null || categories.isBlank()
                ? null : Arrays.asList(categories.split(","));
        ReviewIssueFilter filter = new ReviewIssueFilter(dishUid, menuUid, search, categoryList);
        return analyticsService.getReviewsByIssueForChef(user, issue, filter, rangeStart, rangeEnd);
    }
}
