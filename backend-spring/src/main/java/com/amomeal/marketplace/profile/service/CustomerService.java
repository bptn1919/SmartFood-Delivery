package com.amomeal.marketplace.profile.service;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.service.AttachmentService;
import com.amomeal.marketplace.dish.dto.DishResponse;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.exception.DishNotFoundException;
import com.amomeal.marketplace.dish.service.DishService;
import com.amomeal.marketplace.ingredient.entity.AllergicIngredient;
import com.amomeal.marketplace.ingredient.entity.FavouriteIngredient;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.repository.AllergicIngredientRepository;
import com.amomeal.marketplace.ingredient.repository.FavouriteIngredientRepository;
import com.amomeal.marketplace.ingredient.repository.IngredientRepository;
import com.amomeal.marketplace.profile.dto.CustomerAddressDetailResponse;
import com.amomeal.marketplace.profile.dto.CustomerAddressRequest;
import com.amomeal.marketplace.profile.dto.CustomerAddressResponse;
import com.amomeal.marketplace.profile.dto.CustomerFullProfileResponse;
import com.amomeal.marketplace.profile.dto.CustomerOnboardingRequest;
import com.amomeal.marketplace.profile.dto.CustomerProfileUpdateRequest;
import com.amomeal.marketplace.profile.dto.SetDefaultAddressResponse;
import com.amomeal.marketplace.profile.entity.CustomerAddress;
import com.amomeal.marketplace.profile.entity.CustomerFavoriteDish;
import com.amomeal.marketplace.profile.entity.CustomerProfile;
import com.amomeal.marketplace.profile.exception.FavouriteDishDoesNotExistException;
import com.amomeal.marketplace.profile.exception.HttpBadRequestException;
import com.amomeal.marketplace.profile.exception.ProfileDoesNotExistException;
import com.amomeal.marketplace.profile.repository.CustomerAddressRepository;
import com.amomeal.marketplace.profile.repository.CustomerFavoriteDishRepository;
import com.amomeal.marketplace.profile.repository.CustomerProfileRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Mirrors ../../backend/profile/services/__init__.py::CustomerService (the
 * customer-side of the module — profile, addresses, favourite dishes,
 * onboarding).
 *
 * <p>PORT-NOTE: Django's {@code @sync_user_feature} decorator on
 * {@code add_favorite_dish}/{@code remove_favorite_dish}/
 * {@code update_customer_profile}, and the direct
 * {@code RecommendationService().rebuild_user_feature(...)} call inside
 * {@code onboard_customer_profile}, only ever rebuild the `recommendation`
 * module's feature cache (../../backend/utils/permissions/decorators.py::sync_user_feature)
 * — pure fire-and-forget side effects with no observable contract for this
 * module's own callers. `recommendation` is not ported (CLAUDE.md §7 orders it
 * well after `profile`, and the task instructions for this module explicitly
 * say to model {@code CustomerProfile}'s diet/allergy fields faithfully
 * without building recommendation logic), so these calls are simply omitted
 * rather than stubbed behind a seam interface nothing would ever read yet.
 *
 * <p>PORT-NOTE: {@code onboard_customer_profile} also creates/updates a
 * {@code recommendation.UserDailyNutrition} row from {@code height_cm}/
 * {@code weight_kg}. Same reasoning — `recommendation` owns that table and
 * isn't ported, so those two fields are accepted (schema-compatible with
 * Django) but not persisted anywhere yet. The ingredient-preference half of
 * onboarding (allergic/favourite ingredient rows, `is_onboarded` flag) IS
 * fully ported, since {@code ingredient} already exists.
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerProfileRepository customerProfileRepository;
    private final CustomerAddressRepository customerAddressRepository;
    private final CustomerFavoriteDishRepository customerFavoriteDishRepository;
    private final AllergicIngredientRepository allergicIngredientRepository;
    private final FavouriteIngredientRepository favouriteIngredientRepository;
    private final IngredientRepository ingredientRepository;
    private final AttachmentService attachmentService;
    private final DishService dishService;
    private final CustomerAddressSelectionWriter addressSelectionWriter;

    /** Mirrors {@code CustomerORM.get_customer_profile} — lazy get_or_create. */
    @Transactional
    public CustomerProfile getOrCreateCustomerProfile(CustomUser user) {
        return customerProfileRepository.findByUser(user).orElseGet(() -> customerProfileRepository.save(
                CustomerProfile.builder().user(user).build()));
    }

    @Transactional
    public CustomerFullProfileResponse getCustomerProfile(CustomUser user) {
        CustomerProfile profile = getOrCreateCustomerProfile(user);
        return toFullResponse(profile);
    }

    private CustomerFullProfileResponse toFullResponse(CustomerProfile profile) {
        List<CustomerAddressDetailResponse> addresses = customerAddressRepository.findAllByUser(profile.getUser())
                .stream().map(CustomerAddressDetailResponse::of).toList();
        return CustomerFullProfileResponse.of(profile, addresses);
    }

    /** Mirrors {@code CustomerService.update_customer_profile}. */
    @Transactional
    public CustomerFullProfileResponse updateCustomerProfile(CustomUser user, CustomerProfileUpdateRequest payload) {
        CustomerProfile profile = getOrCreateCustomerProfile(user);

        if (payload.dietMode() != null && payload.dietLevel() == null
                && profile.getDietMode() == com.amomeal.marketplace.profile.entity.DietMode.NONE) {
            throw new HttpBadRequestException("diet_level must be provided when diet_mode is not NONE");
        }
        if (payload.dietMode() == com.amomeal.marketplace.profile.entity.DietMode.NONE
                && payload.dietLevel() != null
                && payload.dietLevel() != com.amomeal.marketplace.profile.entity.DietLevel.NONE) {
            throw new HttpBadRequestException("diet_level must be NONE when diet_mode is NONE");
        }

        // payload.phone() is intentionally never applied — see the DTO's PORT-NOTE.
        if (payload.bio() != null) {
            profile.setBio(payload.bio());
        }
        if (payload.dietMode() != null) {
            profile.setDietMode(payload.dietMode());
        }
        if (payload.dietLevel() != null) {
            profile.setDietLevel(payload.dietLevel());
        }
        if (payload.allergyMode() != null) {
            profile.setAllergyMode(payload.allergyMode());
        }
        if (payload.attachmentUid() != null) {
            Attachment attachment = attachmentService.handleAttachment(payload.attachmentUid());
            profile.setAvatar(attachment);
        }

        CustomerProfile saved = customerProfileRepository.save(profile);
        return toFullResponse(saved);
    }

    /**
     * Mirrors {@code onboard_customer_profile}. Validates the ingredient uids,
     * replaces the user's allergic/favourite ingredient rows, marks the
     * profile onboarded. See class javadoc for the recommendation/daily-
     * nutrition fields that are accepted but not yet persisted.
     */
    @Transactional
    public CustomerFullProfileResponse onboardCustomerProfile(CustomUser user, CustomerOnboardingRequest payload) {
        CustomerProfile profile = getOrCreateCustomerProfile(user);

        List<UUID> allergicUids = dedupe(payload.allergicIngredientUids());
        List<UUID> favoriteUids = dedupe(payload.favoriteIngredientUids());
        Set<UUID> requiredUids = new LinkedHashSet<>();
        requiredUids.addAll(allergicUids);
        requiredUids.addAll(favoriteUids);

        List<Ingredient> found = requiredUids.isEmpty() ? List.of()
                : ingredientRepository.findAllByUidInAndDeletedFalse(List.copyOf(requiredUids));
        var byUid = found.stream().collect(java.util.stream.Collectors.toMap(Ingredient::getUid, i -> i));
        if (byUid.size() != requiredUids.size()) {
            throw new HttpBadRequestException("One or more ingredient_uids are invalid");
        }

        allergicIngredientRepository.findAllByUserAndDeletedFalse(user)
                .forEach(a -> a.setDeleted(true));
        favouriteIngredientRepository.findAllByUserAndDeletedFalse(user)
                .forEach(f -> f.setDeleted(true));

        for (UUID uid : allergicUids) {
            Ingredient ingredient = byUid.get(uid);
            AllergicIngredient row = allergicIngredientRepository.findByUserAndIngredient(user, ingredient)
                    .orElseGet(() -> AllergicIngredient.builder().user(user).ingredient(ingredient).build());
            row.setDeleted(false);
            allergicIngredientRepository.save(row);
        }
        for (UUID uid : favoriteUids) {
            Ingredient ingredient = byUid.get(uid);
            FavouriteIngredient row = favouriteIngredientRepository.findByUserAndIngredient(user, ingredient)
                    .orElseGet(() -> FavouriteIngredient.builder().user(user).ingredient(ingredient).build());
            row.setDeleted(false);
            favouriteIngredientRepository.save(row);
        }

        profile.setOnboarded(true);
        CustomerProfile saved = customerProfileRepository.save(profile);
        return toFullResponse(saved);
    }

    private static List<UUID> dedupe(List<UUID> uids) {
        return List.copyOf(new LinkedHashSet<>(uids));
    }

    // ===================================================================
    // Addresses — PORT-NOTE: none of these filter `deleted`, see
    // CustomerAddress/CustomerAddressRepository's javadoc.
    // ===================================================================

    @Transactional(readOnly = true)
    public List<CustomerAddressResponse> getCustomerAddress(CustomUser user) {
        List<CustomerAddress> addresses = customerAddressRepository.findAllByUser(user);
        if (addresses.isEmpty()) {
            throw new ProfileDoesNotExistException();
        }
        return addresses.stream().map(CustomerAddressResponse::of).toList();
    }

    /** Mirrors {@code CustomerORM.get_one_customer_address}: selected first, else most recently created. */
    @Transactional(readOnly = true)
    public CustomerAddressResponse getOneCustomerAddress(CustomUser user) {
        CustomerAddress address = customerAddressRepository.findFirstByUserAndSelectedTrue(user)
                .or(() -> customerAddressRepository.findFirstByUserOrderByIdDesc(user))
                .orElseThrow(ProfileDoesNotExistException::new);
        return CustomerAddressResponse.of(address);
    }

    /**
     * Mirrors {@code CustomerORM.get_one_customer_address_by_id} — a genuine
     * Django quirk: when the user has ANY selected/default address, it is
     * returned regardless of the requested {@code address_id}; the id is only
     * actually looked up when there is no selected address at all, and in that
     * branch a missing id is an UNCAUGHT {@code CustomerAddress.DoesNotExist}
     * in Django (the service's own {@code if not address: raise
     * ProfileDoesNotExist} is unreachable dead code, since Django's
     * {@code .get()} raises instead of returning falsy) — ported as an
     * uncaught lookup here too (a plain {@link java.util.NoSuchElementException}
     * via {@code Optional.get()}), which {@code GlobalExceptionHandler}'s
     * catch-all maps to the same 500 CONTACT_ADMIN_FOR_SUPPORT Django would
     * produce, not the friendlier 404 the never-reached service check implies.
     */
    @Transactional(readOnly = true)
    public CustomerAddressDetailResponse getOneCustomerAddressById(CustomUser user, Long addressId) {
        CustomerAddress address = customerAddressRepository.findFirstByUserAndSelectedTrue(user)
                .orElseGet(() -> customerAddressRepository.findByUserAndId(user, addressId).get());
        return CustomerAddressDetailResponse.of(address);
    }

    @Transactional
    public CustomerAddressDetailResponse createNewCustomerAddress(CustomUser user, CustomerAddressRequest payload) {
        CustomerAddress address = CustomerAddress.builder()
                .user(user)
                .address(payload.address())
                .street(payload.street())
                .ward(payload.ward())
                .district(payload.district())
                .city(payload.city())
                .latitude(payload.latitude())
                .longitude(payload.longitude())
                .selected(Boolean.TRUE.equals(payload.selected()))
                .deleted(Boolean.TRUE.equals(payload.deleted()))
                .build();
        return CustomerAddressDetailResponse.of(customerAddressRepository.save(address));
    }

    public SetDefaultAddressResponse setDefaultCustomerAddress(CustomUser user, Long addressId) {
        boolean matched = addressSelectionWriter.applySelection(user, addressId);
        if (!matched) {
            throw new ProfileDoesNotExistException();
        }
        return new SetDefaultAddressResponse("Set default address successfully");
    }

    @Transactional
    public boolean softDeleteCustomerAddress(CustomUser user, Long addressId) {
        CustomerAddress address = customerAddressRepository.findByUserAndId(user, addressId)
                .orElseThrow(ProfileDoesNotExistException::new);
        address.setDeleted(true);
        customerAddressRepository.save(address);
        return true;
    }

    // ===================================================================
    // Favorite dishes
    // ===================================================================

    @Transactional
    public DishResponse addFavoriteDish(CustomUser user, UUID dishUid) {
        Dish dish = dishService.getDishEntity(dishUid);
        CustomerFavoriteDish row = customerFavoriteDishRepository.findByUserAndDish(user, dish)
                .orElseGet(() -> CustomerFavoriteDish.builder().user(user).dish(dish).build());
        row.setDeleted(false);
        customerFavoriteDishRepository.save(row);
        return dishService.getDishByUid(dishUid, user);
    }

    @Transactional
    public boolean removeFavoriteDish(CustomUser user, UUID dishUid) {
        CustomerFavoriteDish row = customerFavoriteDishRepository.findByUserAndDishUidAndDeletedFalse(user, dishUid)
                .orElseThrow(FavouriteDishDoesNotExistException::new);
        row.setDeleted(true);
        customerFavoriteDishRepository.save(row);
        return true;
    }

    @Transactional(readOnly = true)
    public List<DishResponse> getFavoriteDishes(CustomUser user) {
        return customerFavoriteDishRepository.findAllByUserAndDeletedFalse(user).stream()
                .map(fav -> {
                    try {
                        return dishService.getDishByUid(fav.getDish().getUid(), user);
                    } catch (DishNotFoundException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
