package com.amomeal.marketplace.profile.service;

import com.amomeal.marketplace.dish.entity.AllergyMode;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.profile.entity.CustomerFavoriteDish;
import com.amomeal.marketplace.profile.entity.CustomerProfile;
import com.amomeal.marketplace.profile.repository.CustomerFavoriteDishRepository;
import com.amomeal.marketplace.profile.repository.CustomerProfileRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProfileDishUserContext} — the real
 * {@code DishUserContext} implementation `dish` left as a seam. Verifies both
 * the "profile exists" path and the exact Django no-row fallback (WARN, no
 * favourites) the default {@code NoProfileDishUserContext} used to hardcode.
 */
@ExtendWith(MockitoExtension.class)
class ProfileDishUserContextTest {

    @Mock private CustomerProfileRepository customerProfileRepository;
    @Mock private CustomerFavoriteDishRepository customerFavoriteDishRepository;

    private ProfileDishUserContext context;

    @BeforeEach
    void setUp() {
        context = new ProfileDishUserContext(customerProfileRepository, customerFavoriteDishRepository);
    }

    @Test
    void allergyMode_noProfileRow_defaultsToWarn_matchingDjangoFallback() {
        when(customerProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        assertThat(context.allergyMode(1L)).isEqualTo(AllergyMode.WARN);
    }

    @Test
    void allergyMode_readsFromExistingProfile() {
        CustomUser user = CustomUser.builder().id(1L).build();
        CustomerProfile profile = CustomerProfile.builder().user(user)
                .allergyMode(AllergyMode.HIDE).build();
        when(customerProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        assertThat(context.allergyMode(1L)).isEqualTo(AllergyMode.HIDE);
    }

    @Test
    void favoriteDishUids_noRows_isEmpty() {
        when(customerFavoriteDishRepository.findAllByUserIdAndDeletedFalse(1L)).thenReturn(List.of());
        assertThat(context.favoriteDishUids(1L)).isEmpty();
    }

    @Test
    void favoriteDishUids_returnsUidsOfNonDeletedFavorites() {
        UUID dishUid = UUID.randomUUID();
        Dish dish = Dish.builder().uid(dishUid).build();
        CustomerFavoriteDish fav = CustomerFavoriteDish.builder().dish(dish).build();
        when(customerFavoriteDishRepository.findAllByUserIdAndDeletedFalse(1L)).thenReturn(List.of(fav));

        assertThat(context.favoriteDishUids(1L)).containsExactly(dishUid);
    }
}
