package com.amomeal.marketplace.profile.service;

import com.amomeal.marketplace.attachment.service.AttachmentService;
import com.amomeal.marketplace.dish.dto.DishResponse;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.exception.DishNotFoundException;
import com.amomeal.marketplace.dish.service.DishService;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.repository.AllergicIngredientRepository;
import com.amomeal.marketplace.ingredient.repository.FavouriteIngredientRepository;
import com.amomeal.marketplace.ingredient.repository.IngredientRepository;
import com.amomeal.marketplace.profile.dto.CustomerOnboardingRequest;
import com.amomeal.marketplace.profile.dto.CustomerProfileUpdateRequest;
import com.amomeal.marketplace.profile.entity.CustomerAddress;
import com.amomeal.marketplace.profile.entity.CustomerFavoriteDish;
import com.amomeal.marketplace.profile.entity.CustomerProfile;
import com.amomeal.marketplace.profile.entity.DietLevel;
import com.amomeal.marketplace.profile.entity.DietMode;
import com.amomeal.marketplace.profile.exception.FavouriteDishDoesNotExistException;
import com.amomeal.marketplace.profile.exception.HttpBadRequestException;
import com.amomeal.marketplace.profile.exception.ProfileDoesNotExistException;
import com.amomeal.marketplace.profile.repository.CustomerAddressRepository;
import com.amomeal.marketplace.profile.repository.CustomerFavoriteDishRepository;
import com.amomeal.marketplace.profile.repository.CustomerProfileRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests (Mockito) for {@link CustomerService} — profile CRUD, the
 * diet_mode/diet_level HttpError-shaped validation, onboarding, favourite
 * dishes, and the address lookup quirks (see {@code CustomerAddress}'s and
 * {@code CustomerAddressRepository}'s javadoc for the Django behavior being
 * preserved).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerServiceTest {

    @Mock private CustomerProfileRepository customerProfileRepository;
    @Mock private CustomerAddressRepository customerAddressRepository;
    @Mock private CustomerFavoriteDishRepository customerFavoriteDishRepository;
    @Mock private AllergicIngredientRepository allergicIngredientRepository;
    @Mock private FavouriteIngredientRepository favouriteIngredientRepository;
    @Mock private IngredientRepository ingredientRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private DishService dishService;
    @Mock private CustomerAddressSelectionWriter addressSelectionWriter;

    private CustomerService service;
    private CustomUser user;

    @BeforeEach
    void setUp() {
        service = new CustomerService(customerProfileRepository, customerAddressRepository,
                customerFavoriteDishRepository, allergicIngredientRepository, favouriteIngredientRepository,
                ingredientRepository, attachmentService, dishService, addressSelectionWriter);
        user = CustomUser.builder().id(1L).username("cust").build();
        when(customerAddressRepository.findAllByUser(user)).thenReturn(List.of());
    }

    private CustomerProfile freshProfile() {
        return CustomerProfile.builder().id(1L).user(user).build();
    }

    // =====================================================================
    // update_customer_profile — diet mode/level HttpError-shaped validation
    // =====================================================================

    @Test
    void updateProfile_settingDietModeWithoutLevel_whenCurrentlyNone_isRejected() {
        CustomerProfile profile = freshProfile(); // dietMode/dietLevel both default NONE
        when(customerProfileRepository.findByUser(user)).thenReturn(Optional.of(profile));

        var payload = new CustomerProfileUpdateRequest(null, null, null, DietMode.LOW_CARB, null, null);

        assertThatThrownBy(() -> service.updateCustomerProfile(user, payload))
                .isInstanceOf(HttpBadRequestException.class);
    }

    @Test
    void updateProfile_settingDietModeNoneWithNonNoneLevel_isRejected() {
        CustomerProfile profile = freshProfile();
        profile.setDietMode(DietMode.LOW_CARB);
        profile.setDietLevel(DietLevel.SOFT);
        when(customerProfileRepository.findByUser(user)).thenReturn(Optional.of(profile));

        var payload = new CustomerProfileUpdateRequest(null, null, null, DietMode.NONE, DietLevel.SOFT, null);

        assertThatThrownBy(() -> service.updateCustomerProfile(user, payload))
                .isInstanceOf(HttpBadRequestException.class);
    }

    @Test
    void updateProfile_dietModeAndLevelTogether_succeeds() {
        CustomerProfile profile = freshProfile();
        when(customerProfileRepository.findByUser(user)).thenReturn(Optional.of(profile));
        when(customerProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var payload = new CustomerProfileUpdateRequest("bio", null, "0900000000", DietMode.LOW_CARB, DietLevel.SOFT, null);
        var response = service.updateCustomerProfile(user, payload);

        assertThat(response.dietMode()).isEqualTo(DietMode.LOW_CARB);
        assertThat(response.dietLevel()).isEqualTo(DietLevel.SOFT);
        assertThat(response.bio()).isEqualTo("bio");
    }

    @Test
    void updateProfile_phoneFieldIsAcceptedButNeverPersisted_matchingDjangoDeadField() {
        CustomerProfile profile = freshProfile();
        when(customerProfileRepository.findByUser(user)).thenReturn(Optional.of(profile));
        when(customerProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var payload = new CustomerProfileUpdateRequest(null, null, "0900000000", null, null, null);
        // Must not throw despite "phone" not being a real CustomerProfile field.
        service.updateCustomerProfile(user, payload);
        // CustomerProfile has no phone field to assert on at all -- the absence
        // of a setter call is the whole point.
    }

    // =====================================================================
    // onboard_customer_profile
    // =====================================================================

    @Test
    void onboard_invalidIngredientUid_throwsHttpBadRequest() {
        CustomerProfile profile = freshProfile();
        when(customerProfileRepository.findByUser(user)).thenReturn(Optional.of(profile));
        UUID missingUid = UUID.randomUUID();
        when(ingredientRepository.findAllByUidInAndDeletedFalse(any())).thenReturn(List.of());

        var payload = new CustomerOnboardingRequest(170.0, 65.0, List.of(missingUid), List.of());

        assertThatThrownBy(() -> service.onboardCustomerProfile(user, payload))
                .isInstanceOf(HttpBadRequestException.class);
    }

    @Test
    void onboard_validIngredients_marksOnboardedAndUpsertsRows() {
        CustomerProfile profile = freshProfile();
        when(customerProfileRepository.findByUser(user)).thenReturn(Optional.of(profile));
        when(customerProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID allergicUid = UUID.randomUUID();
        UUID favoriteUid = UUID.randomUUID();
        Ingredient allergic = Ingredient.builder().uid(allergicUid).build();
        Ingredient favorite = Ingredient.builder().uid(favoriteUid).build();
        when(ingredientRepository.findAllByUidInAndDeletedFalse(any())).thenReturn(List.of(allergic, favorite));
        when(allergicIngredientRepository.findAllByUserAndDeletedFalse(user)).thenReturn(List.of());
        when(favouriteIngredientRepository.findAllByUserAndDeletedFalse(user)).thenReturn(List.of());
        when(allergicIngredientRepository.findByUserAndIngredient(any(), any())).thenReturn(Optional.empty());
        when(favouriteIngredientRepository.findByUserAndIngredient(any(), any())).thenReturn(Optional.empty());

        var payload = new CustomerOnboardingRequest(170.0, 65.0, List.of(allergicUid), List.of(favoriteUid));
        var response = service.onboardCustomerProfile(user, payload);

        assertThat(response.isOnboarded()).isTrue();
        verify(allergicIngredientRepository).save(any());
        verify(favouriteIngredientRepository).save(any());
    }

    // =====================================================================
    // Addresses
    // =====================================================================

    @Test
    void getCustomerAddress_empty_throwsProfileDoesNotExist() {
        assertThatThrownBy(() -> service.getCustomerAddress(user)).isInstanceOf(ProfileDoesNotExistException.class);
    }

    @Test
    void getOneCustomerAddressById_selectedAddressExists_isReturnedRegardlessOfRequestedId() {
        CustomerAddress selected = CustomerAddress.builder().id(1L).user(user).address("A").street("s").ward("w")
                .district("d").city("c").selected(true).build();
        when(customerAddressRepository.findFirstByUserAndSelectedTrue(user)).thenReturn(Optional.of(selected));

        var response = service.getOneCustomerAddressById(user, 999L);

        assertThat(response.id()).isEqualTo(1L);
        // The by-id lookup must never even be consulted when a selected address exists.
        verify(customerAddressRepository, never()).findByUserAndId(any(), any());
    }

    @Test
    void getOneCustomerAddressById_noSelected_fallsBackToRequestedId() {
        when(customerAddressRepository.findFirstByUserAndSelectedTrue(user)).thenReturn(Optional.empty());
        CustomerAddress byId = CustomerAddress.builder().id(7L).user(user).address("A").street("s").ward("w")
                .district("d").city("c").build();
        when(customerAddressRepository.findByUserAndId(user, 7L)).thenReturn(Optional.of(byId));

        var response = service.getOneCustomerAddressById(user, 7L);
        assertThat(response.id()).isEqualTo(7L);
    }

    @Test
    void getOneCustomerAddressById_noSelectedAndIdNotFound_throwsUncaughtNoSuchElement() {
        when(customerAddressRepository.findFirstByUserAndSelectedTrue(user)).thenReturn(Optional.empty());
        when(customerAddressRepository.findByUserAndId(user, 7L)).thenReturn(Optional.empty());

        // PORT-NOTE: Django's own service-level "not found" check is unreachable
        // dead code here (see CustomerService javadoc) -- this uncaught lookup
        // failure is the faithful port, not a bug.
        assertThatThrownBy(() -> service.getOneCustomerAddressById(user, 7L))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void setDefaultAddress_noMatch_throwsProfileDoesNotExist() {
        when(addressSelectionWriter.applySelection(user, 42L)).thenReturn(false);
        assertThatThrownBy(() -> service.setDefaultCustomerAddress(user, 42L))
                .isInstanceOf(ProfileDoesNotExistException.class);
    }

    @Test
    void setDefaultAddress_match_returnsSuccessMessage() {
        when(addressSelectionWriter.applySelection(user, 1L)).thenReturn(true);
        var response = service.setDefaultCustomerAddress(user, 1L);
        assertThat(response.message()).isEqualTo("Set default address successfully");
    }

    // =====================================================================
    // Favorite dishes
    // =====================================================================

    private static DishResponse dishResponse(UUID uid) {
        return new DishResponse(uid, "Phở", null, null, null, null, 0, 0, false, 0, false, false,
                List.of(), null, null, null, null, 0, 0);
    }

    @Test
    void addFavoriteDish_newFavorite_persistsAndReturnsDecoratedDish() {
        UUID dishUid = UUID.randomUUID();
        Dish dish = Dish.builder().uid(dishUid).build();
        when(dishService.getDishEntity(dishUid)).thenReturn(dish);
        when(customerFavoriteDishRepository.findByUserAndDish(user, dish)).thenReturn(Optional.empty());
        DishResponse decorated = dishResponse(dishUid);
        when(dishService.getDishByUid(dishUid, user)).thenReturn(decorated);

        var result = service.addFavoriteDish(user, dishUid);

        assertThat(result).isSameAs(decorated);
        verify(customerFavoriteDishRepository).save(any());
    }

    @Test
    void removeFavoriteDish_notCurrentlyFavorited_throws() {
        UUID dishUid = UUID.randomUUID();
        when(customerFavoriteDishRepository.findByUserAndDishUidAndDeletedFalse(user, dishUid))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.removeFavoriteDish(user, dishUid))
                .isInstanceOf(FavouriteDishDoesNotExistException.class);
    }

    @Test
    void removeFavoriteDish_existing_marksDeleted() {
        UUID dishUid = UUID.randomUUID();
        CustomerFavoriteDish fav = CustomerFavoriteDish.builder().deleted(false).build();
        when(customerFavoriteDishRepository.findByUserAndDishUidAndDeletedFalse(user, dishUid))
                .thenReturn(Optional.of(fav));

        boolean result = service.removeFavoriteDish(user, dishUid);

        assertThat(result).isTrue();
        assertThat(fav.isDeleted()).isTrue();
    }

    @Test
    void getFavoriteDishes_skipsDishesThatNoLongerResolve() {
        Dish dish1 = Dish.builder().uid(UUID.randomUUID()).build();
        Dish dish2 = Dish.builder().uid(UUID.randomUUID()).build();
        CustomerFavoriteDish fav1 = CustomerFavoriteDish.builder().dish(dish1).build();
        CustomerFavoriteDish fav2 = CustomerFavoriteDish.builder().dish(dish2).build();
        when(customerFavoriteDishRepository.findAllByUserAndDeletedFalse(user)).thenReturn(List.of(fav1, fav2));

        DishResponse ok = dishResponse(dish1.getUid());
        when(dishService.getDishByUid(dish1.getUid(), user)).thenReturn(ok);
        when(dishService.getDishByUid(dish2.getUid(), user)).thenThrow(new DishNotFoundException());

        var result = service.getFavoriteDishes(user);

        assertThat(result).containsExactly(ok);
    }
}
