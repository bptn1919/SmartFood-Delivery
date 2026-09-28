package com.amomeal.marketplace.recommendation.web;

import com.amomeal.marketplace.recommendation.dto.*;
import com.amomeal.marketplace.recommendation.service.RecommendationService;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Mirrors ../../backend/recommendation/api.py::RecommendationController
 * ({@code @api(prefix_or_class="recommendation", auth=AuthBear())} → every endpoint needs a token).
 * FE-admin calls none of these (grepped) — the consumer is the customer mobile app.
 *
 * <p>🔴 PORT-NOTE (faithful authorization gap, flagged): the two {@code /users/{user_id}/...}
 * endpoints are decorated {@code @require_admin} ABOVE {@code @get(...)} in Django. ninja-extra's
 * route decorator registers the ORIGINAL function as the route's {@code view_func} and
 * {@code functools.wraps} merely copies that route marker onto the admin wrapper — so the wrapper
 * (the admin check) is never called. Verified in backend/venv:
 * {@code getattr(C.get_user_issue_sensitivity_profile, ROUTE_FUNCTION).route.view_func} is the
 * undecorated function. Net effect in Django: ANY authenticated user can read any user's issue
 * profile / preference features. Ported as-is (no role gate), regression-tested.
 */
@RestController
@RequestMapping("/api/recommendation")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService service;

    @GetMapping("/users/{userId}/profile")
    public UserIssueSensitivityProfileResponse getUserIssueSensitivityProfile(@PathVariable long userId) {
        return service.getUserIssueSensitivityProfile(userId).toResponse();
    }

    @GetMapping("/users/{userId}/features")
    public UserFoodPreferenceFeatureResponse getUserFoodPreferenceFeatures(@PathVariable long userId) {
        return service.getUserFoodPreferenceFeatures(userId);
    }

    @GetMapping("/me/features")
    public UserFoodPreferenceFeatureResponse getMyFoodPreferenceFeatures(@AuthenticationPrincipal CustomUser user) {
        return service.getUserFoodPreferenceFeatures(user.getId());
    }

    @GetMapping("/me/dishes")
    public RecommendationFeedResponse getMyRecommendationFeed(@AuthenticationPrincipal CustomUser user,
                                                              @RequestParam(defaultValue = "20") int limit,
                                                              @RequestParam(defaultValue = "0") int offset,
                                                              @RequestParam(name = "include_explain", defaultValue = "false") boolean includeExplain) {
        return service.getRecommendedDishes(user.getId(), limit, offset, includeExplain);
    }

    @PostMapping("/me/daily-nutrition/init")
    public DailyNutritionSummaryResponse initDailyNutrition(@AuthenticationPrincipal CustomUser user,
                                                            @Valid @RequestBody DailyNutritionInitRequest payload) {
        return service.initDailyNutrition(user.getId(), payload.age(), payload.gender(), payload.heightCm(),
                payload.weightKg(), payload.activityLevel(), payload.goal());
    }

    @GetMapping("/me/daily-nutrition/summary")
    public DailyNutritionSummaryResponse getMyDailyNutritionSummary(@AuthenticationPrincipal CustomUser user) {
        return service.getDailyNutritionSummary(user.getId());
    }

    @GetMapping("/me/daily-nutrition/meals")
    public DailyMealLogListResponse getMyDailyMealLogs(@AuthenticationPrincipal CustomUser user) {
        return service.getDailyMealLogs(user.getId());
    }

    @PostMapping("/me/daily-nutrition/parse-meal")
    public DailyNutritionMealParseResponse parseMyDailyMeal(@AuthenticationPrincipal CustomUser user,
                                                            @Valid @RequestBody DailyNutritionParseMealRequest payload) {
        return service.parseDailyMeal(user.getId(), payload.text(), payload.mealTime());
    }

    @GetMapping("/me/daily-nutrition/balanced-recommendations")
    public DailyNutritionRecommendationResponse getMyDailyBalancedRecommendations(@AuthenticationPrincipal CustomUser user,
                                                                                  @RequestParam(defaultValue = "10") int limit) {
        return service.getDailyBalancedRecommendations(user.getId(), limit);
    }

    @PatchMapping("/me/daily-nutrition/meals/{mealUid}")
    public DailyMealLogListResponse updateMyDailyMealLog(@AuthenticationPrincipal CustomUser user,
                                                         @PathVariable String mealUid,
                                                         @Valid @RequestBody DailyMealLogUpdateRequest payload) {
        return service.updateDailyMealLog(user.getId(), mealUid, payload.mealName(), payload.mealTime(),
                payload.quantityMultiplier(), payload.dishUid());
    }

    @DeleteMapping("/me/daily-nutrition/meals/{mealUid}")
    public DailyMealLogListResponse deleteMyDailyMealLog(@AuthenticationPrincipal CustomUser user,
                                                         @PathVariable String mealUid) {
        return service.deleteDailyMealLog(user.getId(), mealUid);
    }

    @PatchMapping("/me/daily-nutrition/profile")
    public DailyNutritionProfileResponse updateMyDailyNutritionProfile(@AuthenticationPrincipal CustomUser user,
                                                                       @RequestBody DailyNutritionProfileUpdateRequest payload) {
        return service.updateDailyNutritionProfile(user.getId(), payload.age(), payload.gender(), payload.heightCm(),
                payload.weightKg(), payload.activityLevel(), payload.goal());
    }

    @GetMapping("/me/daily-nutrition/profile")
    public DailyNutritionProfileResponse getMyDailyNutritionProfile(@AuthenticationPrincipal CustomUser user) {
        return service.getDailyNutritionProfile(user.getId());
    }

    @GetMapping("/me/dishes/{dishUid}/better-for-issue")
    public BetterDishForIssueResponse findBetterDishForIssue(@AuthenticationPrincipal CustomUser user,
                                                            @PathVariable String dishUid,
                                                            @RequestParam(defaultValue = "5") int limit) {
        limit = Math.max(1, Math.min(limit, 20));
        return service.findBetterDishForIssue(user.getId(), dishUid, limit);
    }
}
