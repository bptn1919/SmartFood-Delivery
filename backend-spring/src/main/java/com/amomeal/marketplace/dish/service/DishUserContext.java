package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.dish.entity.AllergyMode;

import java.util.Set;
import java.util.UUID;

/**
 * The two per-customer signals the dish endpoints read out of the `profile`
 * app, which is not ported yet (CLAUDE.md §7 orders it after `dish`):
 * <ul>
 *   <li>{@code CustomerProfile.allergy_mode} — WARN (annotate the response) vs
 *       HIDE (drop the dish from lists / 404 the detail endpoint);</li>
 *   <li>{@code CustomerFavoriteDish} — drives the {@code is_favorite} flag.</li>
 * </ul>
 *
 * <p>Same seam rationale as {@link DishStatsProvider}. The default
 * {@link NoProfileDishUserContext} returns exactly what Django returns for a
 * user with no CustomerProfile row and no favourites — which is Djangos own
 * documented fallback, not an invention of this port:
 * {@code allergy_mode = (profile.allergy_mode if profile else AllergyModeEnum.WARN)}.
 *
 * <p>The allergic-ingredient list itself is NOT here: it lives on
 * {@code ingredient.AllergicIngredient}, which IS ported, so the dish services
 * read it directly from that repository.
 */
public interface DishUserContext {

    AllergyMode allergyMode(Long userId);

    Set<UUID> favoriteDishUids(Long userId);
}
