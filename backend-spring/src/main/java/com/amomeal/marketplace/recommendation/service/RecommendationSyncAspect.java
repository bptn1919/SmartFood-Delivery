package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.profile.dto.CustomerOnboardingRequest;
import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The cross-module side effects Django wires into OTHER apps' service methods, re-attached here
 * WITHOUT editing those modules' source (AOP {@code @AfterReturning} on their public service
 * methods):
 *
 * <ul>
 *   <li>{@code utils/permissions/decorators.py::sync_user_feature} decorates
 *       {@code IngredientService.add/remove_favourite} ("fav_ingredient"),
 *       {@code add/remove_allergic} ("allergy"), {@code CustomerService.add/remove_favorite_dish}
 *       ("fav_dish") and {@code update_customer_profile} ("diet"): after the method returns a
 *       result that is not None/False, {@code RecommendationService().rebuild_user_feature(user.id,
 *       update_fields=[...])} runs (whose post_save signal then refreshes the user vector on commit).</li>
 *   <li>{@code CustomerService.onboard_customer_profile} writes today's {@code UserDailyNutrition}
 *       (height/weight + targets) and calls {@code rebuild_user_feature(["allergy","fav_ingredient"])}.</li>
 *   <li>{@code OrderService.complete_order_with_release} registers
 *       {@code transaction.on_commit(RecommendationService().sync_order_meal_logs(order_uid))},
 *       wrapped in try/except-print.</li>
 * </ul>
 * These were documented as omitted by the profile/order ports ("recommendation not ported"); this
 * closes them. The order-completed / DishIngredient-saved signals (vector refresh) live in
 * {@link RecommendationEntityListeners} instead, because Django attaches those to model saves,
 * not to a service method.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RecommendationSyncAspect {

    private final RecommendationService recommendationService;
    private final DailyNutritionService dailyNutritionService;
    private final PostCommitRunner postCommit;

    private void syncUserFeature(JoinPoint jp, Object result, String field) {
        if (result == null || Boolean.FALSE.equals(result)) {
            return;
        }
        Object first = jp.getArgs().length > 0 ? jp.getArgs()[0] : null;
        if (!(first instanceof CustomUser user) || user.getId() == null) {
            return;
        }
        long userId = user.getId();
        postCommit.afterCommitInNewTransaction(() -> recommendationService.rebuildUserFeature(userId, List.of(field)));
    }

    @AfterReturning(pointcut = "execution(* com.amomeal.marketplace.ingredient.service.IngredientPreferenceService.addFavourite(..))"
            + " || execution(* com.amomeal.marketplace.ingredient.service.IngredientPreferenceService.removeFavourite(..))",
            returning = "result")
    public void afterFavouriteIngredientChange(JoinPoint jp, Object result) {
        syncUserFeature(jp, result, "fav_ingredient");
    }

    @AfterReturning(pointcut = "execution(* com.amomeal.marketplace.ingredient.service.IngredientPreferenceService.addAllergic(..))"
            + " || execution(* com.amomeal.marketplace.ingredient.service.IngredientPreferenceService.removeAllergic(..))",
            returning = "result")
    public void afterAllergicIngredientChange(JoinPoint jp, Object result) {
        syncUserFeature(jp, result, "allergy");
    }

    @AfterReturning(pointcut = "execution(* com.amomeal.marketplace.profile.service.CustomerService.addFavoriteDish(..))"
            + " || execution(* com.amomeal.marketplace.profile.service.CustomerService.removeFavoriteDish(..))",
            returning = "result")
    public void afterFavoriteDishChange(JoinPoint jp, Object result) {
        syncUserFeature(jp, result, "fav_dish");
    }

    @AfterReturning(pointcut = "execution(* com.amomeal.marketplace.profile.service.CustomerService.updateCustomerProfile(..))",
            returning = "result")
    public void afterCustomerProfileUpdate(JoinPoint jp, Object result) {
        syncUserFeature(jp, result, "diet");
    }

    @AfterReturning(pointcut = "execution(* com.amomeal.marketplace.profile.service.CustomerService.onboardCustomerProfile(..))",
            returning = "result")
    public void afterOnboarding(JoinPoint jp, Object result) {
        Object[] args = jp.getArgs();
        if (args.length < 2 || !(args[0] instanceof CustomUser user) || user.getId() == null
                || !(args[1] instanceof CustomerOnboardingRequest payload)) {
            return;
        }
        long userId = user.getId();
        postCommit.afterCommitInNewTransaction(() -> {
            dailyNutritionService.applyOnboardingBodyMetrics(userId, payload.heightCm(), payload.weightKg());
            recommendationService.rebuildUserFeature(userId, List.of("allergy", "fav_ingredient"));
        });
    }

    @AfterReturning(pointcut = "execution(* com.amomeal.marketplace.order.service.OrderService.completeOrderWithRelease(..))")
    public void afterOrderCompleted(JoinPoint jp) {
        if (jp.getArgs().length < 1 || !(jp.getArgs()[0] instanceof UUID orderUid)) {
            return;
        }
        postCommit.afterCommitInNewTransactionLogged(
                () -> recommendationService.syncOrderMealLogs(orderUid.toString()),
                "[Order] Warning: failed to sync daily meal logs: ");
    }
}
