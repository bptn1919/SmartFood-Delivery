package com.amomeal.marketplace.dish.web;

import com.amomeal.marketplace.dish.dto.DishLocationCreateRequest;
import com.amomeal.marketplace.dish.dto.DishLocationResponse;
import com.amomeal.marketplace.dish.dto.DishLocationShortResponse;
import com.amomeal.marketplace.dish.dto.DishLocationTreeResponse;
import com.amomeal.marketplace.dish.dto.DishLocationUpdateRequest;
import com.amomeal.marketplace.dish.entity.DishLocationType;
import com.amomeal.marketplace.dish.service.DishLocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Mirrors ../../backend/dish/api.py::DishLocationController. Paths verified
 * against FE-admin/src/utils/constants.js DISH_LOCATIONS block
 * (CREATE/LIST//tree/DETAIL/UPDATE/DELETE) and
 * FE-admin/src/services/dishLocationService.js.
 *
 * <p>create/update/delete are {@code @require_group(ADMIN)} in Django and now
 * gate on the real ADMIN role ({@code hasRole('ADMIN')}) — the earlier
 * {@code ROLE_STAFF} stand-in is gone, see DishController's javadoc and
 * PROGRESS.md "Part 1 findings". Read endpoints are open to any authenticated
 * user, as in Django.
 */
@RestController
@RequestMapping("/api/dish-locations")
@RequiredArgsConstructor
public class DishLocationController {

    private final DishLocationService dishLocationService;

    @PostMapping({"", "/"})
    @PreAuthorize("hasRole('ADMIN')")
    public DishLocationResponse createDishLocation(@Valid @RequestBody DishLocationCreateRequest payload) {
        return dishLocationService.createDishLocation(payload);
    }

    /** Django: a null parent_id means ROOT level only, not "any parent". */
    @GetMapping({"", "/"})
    public List<DishLocationResponse> getDishLocations(
            @RequestParam(name = "parent_id", required = false) Long parentId,
            @RequestParam(required = false) DishLocationType type) {
        return dishLocationService.getDishLocations(parentId, type);
    }

    @GetMapping("/tree")
    public List<DishLocationTreeResponse> getDishLocationTree() {
        return dishLocationService.getDishLocationTree();
    }

    @GetMapping("/countries")
    public List<DishLocationShortResponse> getCountryLocations() {
        return dishLocationService.getCountryLocations();
    }

    @GetMapping("/{locationId}")
    public DishLocationResponse getDishLocation(@PathVariable Long locationId) {
        return dishLocationService.getDishLocationById(locationId);
    }

    @PatchMapping("/{locationId}")
    @PreAuthorize("hasRole('ADMIN')")
    public DishLocationResponse updateDishLocation(@PathVariable Long locationId,
                                                   @Valid @RequestBody DishLocationUpdateRequest payload) {
        return dishLocationService.updateDishLocation(locationId, payload);
    }

    @DeleteMapping("/{locationId}")
    @PreAuthorize("hasRole('ADMIN')")
    public boolean deleteDishLocation(@PathVariable Long locationId) {
        return dishLocationService.deleteDishLocation(locationId);
    }
}
