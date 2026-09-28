package com.amomeal.marketplace.dish.web;

import com.amomeal.marketplace.dish.dto.DishIngredientDetailResponse;
import com.amomeal.marketplace.dish.dto.DishIngredientSaveResponse;
import com.amomeal.marketplace.dish.dto.DishIngredientUpdateRequest;
import com.amomeal.marketplace.dish.service.DishService;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Mirrors ../../backend/dish/api.py::DishIngredientController. Paths verified
 * against FE-admin/src/utils/constants.js
 * ({@code DISH_INGREDIENT_DETAIL}/{@code DISH_INGREDIENT_UPDATE}/
 * {@code DISH_INGREDIENT_SOFT_DELETE} -&gt; /api/dishingredients/{uid}[/soft-deleted]).
 *
 * <p>Django gates all three with
 * {@code @require_object_permission('dish.change_dish', DishIngredient,
 * owner_field='created_by')}. PORT-NOTE: this port checks ownership of the
 * parent DISH instead ({@code DishService.assertCanModify}) — see that method.
 * The two differ only for a dish whose ingredient rows were created by someone
 * other than the dish owner, which the chef-facing flows never produce.
 */
@RestController
@RequestMapping("/api/dishingredients")
@RequiredArgsConstructor
public class DishIngredientController {

    private final DishService dishService;

    /** Django: {@code GET /api/dishingredients/{uid}}. */
    @GetMapping("/{uid}")
    public DishIngredientDetailResponse getDishIngredient(@PathVariable UUID uid) {
        return dishService.getDishIngredientByUid(uid);
    }

    /** Django: {@code PATCH /api/dishingredients/{uid}}. */
    @PatchMapping("/{uid}")
    public DishIngredientSaveResponse updateDishIngredient(@AuthenticationPrincipal CustomUser user,
                                                           @PathVariable UUID uid,
                                                           @Valid @RequestBody DishIngredientUpdateRequest payload) {
        return dishService.updateDishIngredient(user, uid, payload);
    }

    /** Django: {@code PATCH /api/dishingredients/{uid}/soft-deleted}. */
    @PatchMapping("/{uid}/soft-deleted")
    public boolean softDeleteDishIngredient(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        return dishService.softDeleteDishIngredient(user, uid);
    }
}
