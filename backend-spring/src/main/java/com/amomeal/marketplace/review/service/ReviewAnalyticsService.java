package com.amomeal.marketplace.review.service;

import com.amomeal.marketplace.ingredient.service.RemoveAccents;
import com.amomeal.marketplace.review.dto.ChefDishIssueReport;
import com.amomeal.marketplace.review.dto.ChefIssueReportResponse;
import com.amomeal.marketplace.review.dto.DishIssueWindow;
import com.amomeal.marketplace.review.dto.IssueHeatmapResponse;
import com.amomeal.marketplace.review.dto.IssueReviewsResponse;
import com.amomeal.marketplace.review.dto.IssueTrendPoint;
import com.amomeal.marketplace.review.dto.IssueTrendResponse;
import com.amomeal.marketplace.review.dto.ReviewDetailResponse;
import com.amomeal.marketplace.review.dto.ReviewDishInfo;
import com.amomeal.marketplace.review.dto.ReviewIssueFilter;
import com.amomeal.marketplace.review.dto.ReviewOfDishResponse;
import com.amomeal.marketplace.review.dto.TopComplainedDish;
import com.amomeal.marketplace.review.dto.TopComplainedDishesResponse;
import com.amomeal.marketplace.review.entity.Review;
import com.amomeal.marketplace.review.repository.ReviewRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 1:1 port of ../../backend/review/services/analytics.py::AnalyticsService.
 *
 * <p>Grouping (day buckets, dish totals, issue heatmap cells) is done in Java
 * over a pre-filtered row set rather than as SQL {@code GROUP BY}/{@code
 * TruncDate} annotations — the expected data volume (one chef's flagged
 * reviews over a 14/30-day window) is small, this endpoint has zero FE-admin
 * consumers (grepped {@code FE-admin/src} — no match for any {@code review}
 * endpoint), and it keeps the port DB-agnostic. Date bucketing uses UTC
 * ({@link ZoneOffset#UTC}) rather than Django's server-local {@code timezone.localdate()} —
 * a cosmetic difference with no observed consumer.
 *
 * <p><b>PORT-NOTE — scope reduction, flagged (see PROGRESS.md):</b> Django's
 * {@code FilterIssueReviewSchema} supports {@code dish_uid}/{@code search}
 * (ported, applied in SQL) and {@code menu_uid}/{@code categories} (list/join
 * shaped filters — NOT ported in this pass, same class of decision as
 * {@code ingredient}'s suggestion-approval simplification while `dish` wasn't
 * ported yet). {@code menu_uid} would need a cross-module join into `menu`'s
 * {@code MenuDish}; skipped because this analytics surface has no known
 * consumer (FE-admin doesn't call it) and the core review flows the task
 * emphasizes (create/update/delete/reply/AI/stats-seam) do not depend on it.
 */
@Service
@RequiredArgsConstructor
public class ReviewAnalyticsService {

    private static final int TREND_DAYS = 14;
    private static final int TOP_DISH_DAYS = 30;
    private static final int HEATMAP_DAYS = 30;
    private static final int TOP_DISH_LIMIT = 5;

    private final ReviewRepository reviewRepository;

    @Transactional(readOnly = true)
    public IssueTrendResponse issueTrend(CustomUser user, LocalDate rangeStart, LocalDate rangeEnd) {
        Long chefId = user.getId();
        LocalDate[] range = resolveDateRange(rangeStart, rangeEnd, TREND_DAYS);
        Instant startAt = range[0].atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endAt = range[1].plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<Object[]> rows = reviewRepository.issueDatesForChef(chefId, startAt, endAt);
        List<LocalDate> datePoints = dateRange(range[0], range[1]);

        Set<String> issues = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Map<LocalDate, Integer>> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String issue = (String) row[0];
            LocalDate day = ((Instant) row[1]).atZone(ZoneOffset.UTC).toLocalDate();
            issues.add(issue);
            counts.computeIfAbsent(issue, k -> new LinkedHashMap<>()).merge(day, 1, Integer::sum);
        }

        Map<String, List<IssueTrendPoint>> trend = new LinkedHashMap<>();
        for (String issue : issues) {
            Map<LocalDate, Integer> byDay = counts.getOrDefault(issue, Map.of());
            List<IssueTrendPoint> points = new ArrayList<>();
            for (LocalDate day : datePoints) {
                points.add(new IssueTrendPoint(day, byDay.getOrDefault(day, 0)));
            }
            trend.put(issue, points);
        }
        return new IssueTrendResponse(range[0], range[1], trend);
    }

    @Transactional(readOnly = true)
    public TopComplainedDishesResponse topComplainedDishes(CustomUser user, LocalDate rangeStart, LocalDate rangeEnd, Integer limit) {
        Long chefId = user.getId();
        LocalDate[] range = resolveDateRange(rangeStart, rangeEnd, TOP_DISH_DAYS);
        Instant startAt = range[0].atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endAt = range[1].plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        int topLimit = limit != null ? limit : TOP_DISH_LIMIT;

        List<Object[]> rows = reviewRepository.chefIssueStats(chefId, startAt, endAt);
        Map<UUID, Object[]> perDish = new LinkedHashMap<>(); // uid -> {name, count}
        for (Object[] row : rows) {
            UUID dishUid = (UUID) row[0];
            String dishName = (String) row[1];
            long count = (Long) row[3];
            Object[] existing = perDish.get(dishUid);
            long total = (existing == null ? 0L : (Long) existing[1]) + count;
            perDish.put(dishUid, new Object[]{dishName, total});
        }

        List<TopComplainedDish> items = perDish.entrySet().stream()
                .map(e -> new TopComplainedDish(e.getKey(), (String) e.getValue()[0], ((Long) e.getValue()[1]).intValue()))
                .sorted(Comparator.comparingInt(TopComplainedDish::count).reversed()
                        .thenComparing(TopComplainedDish::dishName, String.CASE_INSENSITIVE_ORDER))
                .limit(topLimit)
                .toList();

        return new TopComplainedDishesResponse(range[0], range[1], topLimit, items);
    }

    @Transactional(readOnly = true)
    public IssueHeatmapResponse issueHeatmap(CustomUser user, LocalDate rangeStart, LocalDate rangeEnd) {
        Long chefId = user.getId();
        LocalDate[] range = resolveDateRange(rangeStart, rangeEnd, HEATMAP_DAYS);
        Instant startAt = range[0].atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endAt = range[1].plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<Object[]> rows = reviewRepository.chefIssueStats(chefId, startAt, endAt);
        Map<String, Map<String, Integer>> heatmap = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String dishName = (String) row[1];
            String issue = (String) row[2];
            long count = (Long) row[3];
            heatmap.computeIfAbsent(dishName, k -> new LinkedHashMap<>()).put(issue, (int) count);
        }
        return new IssueHeatmapResponse(range[0], range[1], heatmap);
    }

    /** Django: {@code ReviewService.get_chef_issue_report}. */
    @Transactional(readOnly = true)
    public ChefIssueReportResponse chefIssueReport(CustomUser user) {
        Long chefId = user.getId();
        Instant now = Instant.now();
        Instant thisWeekStart = now.minusSeconds(7L * 24 * 3600);
        Instant previousWeekStart = now.minusSeconds(14L * 24 * 3600);

        List<Object[]> thisWeekRows = reviewRepository.chefIssueStats(chefId, thisWeekStart, now);
        List<Object[]> previousWeekRows = reviewRepository.chefIssueStats(chefId, previousWeekStart, thisWeekStart);

        record DishAgg(UUID uid, String name, Map<String, Integer> thisWeek, Map<String, Integer> lastWeek) {
        }
        Map<String, DishAgg> byDish = new LinkedHashMap<>();

        for (Object[] row : thisWeekRows) {
            UUID uid = (UUID) row[0];
            String name = (String) row[1];
            DishAgg agg = byDish.computeIfAbsent(uid.toString(),
                    k -> new DishAgg(uid, name, new LinkedHashMap<>(), new LinkedHashMap<>()));
            agg.thisWeek().put((String) row[2], ((Long) row[3]).intValue());
        }
        for (Object[] row : previousWeekRows) {
            UUID uid = (UUID) row[0];
            String name = (String) row[1];
            DishAgg agg = byDish.computeIfAbsent(uid.toString(),
                    k -> new DishAgg(uid, name, new LinkedHashMap<>(), new LinkedHashMap<>()));
            agg.lastWeek().put((String) row[2], ((Long) row[3]).intValue());
        }

        List<ChefDishIssueReport> reports = byDish.values().stream()
                .sorted(Comparator.comparing(DishAgg::name, String.CASE_INSENSITIVE_ORDER))
                .map(agg -> new ChefDishIssueReport(agg.uid(), agg.name(),
                        new DishIssueWindow(!agg.thisWeek().isEmpty(),
                                agg.thisWeek().values().stream().mapToInt(Integer::intValue).sum(), agg.thisWeek()),
                        new DishIssueWindow(!agg.lastWeek().isEmpty(),
                                agg.lastWeek().values().stream().mapToInt(Integer::intValue).sum(), agg.lastWeek())))
                .toList();

        return new ChefIssueReportResponse(previousWeekStart, now, thisWeekStart, reports);
    }

    /** Django: {@code AnalyticsService.get_reviews_by_issue_for_chef}. */
    @Transactional(readOnly = true)
    public IssueReviewsResponse getReviewsByIssueForChef(CustomUser user, String issue, ReviewIssueFilter filter,
                                                           LocalDate rangeStart, LocalDate rangeEnd) {
        Long chefId = user.getId();
        LocalDate[] range = resolveDateRange(rangeStart, rangeEnd, HEATMAP_DAYS);
        Instant startAt = range[0].atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endAt = range[1].plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        ReviewIssueFilter f = filter == null ? ReviewIssueFilter.EMPTY : filter;
        String searchNoAccent = f.search() == null || f.search().isBlank() ? null : RemoveAccents.apply(f.search());

        List<Review> reviews = reviewRepository.findIssueReviewsForChef(chefId, issue, startAt, endAt, f.dishUid(), searchNoAccent);
        if (f.categories() != null && !f.categories().isEmpty()) {
            Set<String> allowed = new LinkedHashSet<>(f.categories());
            reviews = reviews.stream().filter(r -> allowed.contains(r.getDish().getCategory().name())).toList();
        }

        Map<UUID, ReviewOfDishResponse> dishMap = new LinkedHashMap<>();
        Map<UUID, List<ReviewDetailResponse>> reviewsByDish = new LinkedHashMap<>();
        int total = 0;
        for (Review review : reviews) {
            UUID dishUid = review.getDish().getUid();
            dishMap.computeIfAbsent(dishUid, k -> new ReviewOfDishResponse(ReviewDishInfo.of(review.getDish()), new ArrayList<>()));
            reviewsByDish.computeIfAbsent(dishUid, k -> new ArrayList<>()).add(ReviewDetailResponse.of(review));
            total++;
        }
        List<ReviewOfDishResponse> grouped = dishMap.entrySet().stream()
                .map(e -> new ReviewOfDishResponse(e.getValue().dishInfo(), reviewsByDish.get(e.getKey())))
                .toList();

        return new IssueReviewsResponse(range[0], range[1], issue, total, grouped);
    }

    private static LocalDate[] resolveDateRange(LocalDate rangeStart, LocalDate rangeEnd, int defaultDays) {
        LocalDate end = rangeEnd != null ? rangeEnd : LocalDate.now(ZoneOffset.UTC);
        LocalDate start = rangeStart != null ? rangeStart : end.minusDays(defaultDays);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("range_start must be less than or equal to range_end");
        }
        return new LocalDate[]{start, end};
    }

    private static List<LocalDate> dateRange(LocalDate start, LocalDate end) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            days.add(d);
        }
        return days;
    }
}
