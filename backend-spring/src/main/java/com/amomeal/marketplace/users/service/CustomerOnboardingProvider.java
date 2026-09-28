package com.amomeal.marketplace.users.service;

/**
 * Seam for {@code CustomUser.is_onboarded}
 * ({@code ../backend/users/models.py}), which reads
 * {@code self.customer_profile.is_onboarded} and returns {@code False} when the
 * user has no {@code CustomerProfile} row at all
 * ({@code except ObjectDoesNotExist: return False}).
 *
 * <p>The {@code profile} module is not ported yet (CLAUDE.md §7 port order), so
 * the default implementation returns {@code false} — which is literally Django's
 * own behavior on a platform with no profile rows, not a guess. Same pattern as
 * {@code dish}'s {@code DishStatsProvider}/{@code DishUserContext} seams:
 * {@code profile} will register a {@code @Primary} bean over this one.
 * {@code @ConditionalOnMissingBean} is deliberately NOT used — it does not fire
 * for component-scanned {@code @Component}s (documented in
 * {@code NoProfileDishUserContext}, cost a debugging cycle there).
 */
public interface CustomerOnboardingProvider {

    boolean isOnboarded(Long userId);
}
