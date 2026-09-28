package com.amomeal.marketplace.ingredient.service;

import com.amomeal.marketplace.ingredient.dto.UserIngredientPreferenceRequest;
import com.amomeal.marketplace.ingredient.dto.UserIngredientPreferenceResponse;
import com.amomeal.marketplace.ingredient.entity.AllergicIngredient;
import com.amomeal.marketplace.ingredient.entity.FavouriteIngredient;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.repository.AllergicIngredientRepository;
import com.amomeal.marketplace.ingredient.repository.FavouriteIngredientRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/ingredient/services/__init__.py::IngredientPreferenceService
 * — per-user favourite/allergic ingredient signals consumed by
 * `recommendation` (../../backend/recommendation/services/recommendation.py,
 * confirmed by grep) for scoring/allergy hide-or-warn logic.
 *
 * <p>PORT-NOTE: Django wraps each mutator in {@code @sync_user_feature("user",
 * update_fields=[...])}, which re-syncs a denormalized {@code fav_ingredient}/
 * {@code allergy} field on the user's profile row after the write (a
 * `profile`-module concern — ../../backend/profile/services/__init__.py shows
 * {@code AllergicIngredient}/{@code FavouriteIngredient} rows are also
 * mirrored there). `profile` isn't ported yet — that sync is out of scope
 * here and deferred to `profile`'s own port; this service only writes the
 * ingredient-module rows Django itself writes directly.
 */
@Service
@RequiredArgsConstructor
public class IngredientPreferenceService {

    private final IngredientService ingredientService;
    private final FavouriteIngredientRepository favouriteIngredientRepository;
    private final AllergicIngredientRepository allergicIngredientRepository;

    @Transactional
    public UserIngredientPreferenceResponse addFavourite(CustomUser user, UserIngredientPreferenceRequest payload) {
        Ingredient ingredient = ingredientService.getByUidOrThrow(payload.ingredientUid());
        FavouriteIngredient favourite = favouriteIngredientRepository.findByUserAndIngredient(user, ingredient)
                .orElseGet(FavouriteIngredient::new);
        favourite.setUser(user);
        favourite.setIngredient(ingredient);
        favourite.setDeleted(false);
        favouriteIngredientRepository.save(favourite);
        return UserIngredientPreferenceResponse.from(ingredient);
    }

    @Transactional
    public boolean removeFavourite(CustomUser user, UUID ingredientUid) {
        Ingredient ingredient = ingredientService.getByUidOrThrow(ingredientUid);
        FavouriteIngredient favourite = favouriteIngredientRepository.findByUserAndIngredient(user, ingredient)
                .orElseThrow(); // Mirrors Django's FavouriteIngredient.objects.get(...) -> DoesNotExist (uncaught, 500)
        favourite.setDeleted(true);
        favouriteIngredientRepository.save(favourite);
        return true;
    }

    @Transactional(readOnly = true)
    public List<UserIngredientPreferenceResponse> listFavourites(CustomUser user) {
        return favouriteIngredientRepository.findAllByUserAndDeletedFalse(user).stream()
                .map(FavouriteIngredient::getIngredient)
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(UserIngredientPreferenceResponse::from)
                .toList();
    }

    @Transactional
    public UserIngredientPreferenceResponse addAllergic(CustomUser user, UserIngredientPreferenceRequest payload) {
        Ingredient ingredient = ingredientService.getByUidOrThrow(payload.ingredientUid());
        AllergicIngredient allergic = allergicIngredientRepository.findByUserAndIngredient(user, ingredient)
                .orElseGet(AllergicIngredient::new);
        allergic.setUser(user);
        allergic.setIngredient(ingredient);
        allergic.setDeleted(false);
        allergicIngredientRepository.save(allergic);
        return UserIngredientPreferenceResponse.from(ingredient);
    }

    @Transactional
    public boolean removeAllergic(CustomUser user, UUID ingredientUid) {
        Ingredient ingredient = ingredientService.getByUidOrThrow(ingredientUid);
        AllergicIngredient allergic = allergicIngredientRepository.findByUserAndIngredient(user, ingredient)
                .orElseThrow();
        allergic.setDeleted(true);
        allergicIngredientRepository.save(allergic);
        return true;
    }

    @Transactional(readOnly = true)
    public List<UserIngredientPreferenceResponse> listAllergic(CustomUser user) {
        return allergicIngredientRepository.findAllByUserAndDeletedFalse(user).stream()
                .map(AllergicIngredient::getIngredient)
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(UserIngredientPreferenceResponse::from)
                .toList();
    }
}
