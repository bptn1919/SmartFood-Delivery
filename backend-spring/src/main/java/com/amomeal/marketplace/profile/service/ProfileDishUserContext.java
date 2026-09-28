package com.amomeal.marketplace.profile.service;

import com.amomeal.marketplace.dish.entity.AllergyMode;
import com.amomeal.marketplace.dish.service.DishUserContext;
import com.amomeal.marketplace.profile.repository.CustomerFavoriteDishRepository;
import com.amomeal.marketplace.profile.repository.CustomerProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The real {@link DishUserContext}, now that {@code CustomerProfile}/
 * {@code CustomerFavoriteDish} exist. Replaces `dish`'s
 * {@code NoProfileDishUserContext} default via {@code @Primary} (that class's
 * javadoc explains why {@code @ConditionalOnMissingBean} isn't used instead).
 *
 * <p>Read-only lookups only, deliberately — this does NOT
 * {@code get_or_create} a {@code CustomerProfile} row as a side effect of a
 * dish read. Django's own fallback for a user with no profile row is
 * {@code AllergyModeEnum.WARN} (the "if profile else WARN" comment `dish`'s
 * seam javadoc quotes); a row is only ever created lazily when the customer
 * actually visits a profile-owned endpoint ({@code ProfileService}/
 * {@code CustomerService}'s own {@code get_or_create} calls), exactly like
 * Django.
 */
@Component
@Primary
@RequiredArgsConstructor
public class ProfileDishUserContext implements DishUserContext {

    private final CustomerProfileRepository customerProfileRepository;
    private final CustomerFavoriteDishRepository customerFavoriteDishRepository;

    @Override
    @Transactional(readOnly = true)
    public AllergyMode allergyMode(Long userId) {
        return customerProfileRepository.findByUserId(userId)
                .map(p -> switch (p.getAllergyMode()) {
                    case WARN -> AllergyMode.WARN;
                    case HIDE -> AllergyMode.HIDE;
                })
                .orElse(AllergyMode.WARN);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> favoriteDishUids(Long userId) {
        return customerFavoriteDishRepository.findAllByUserIdAndDeletedFalse(userId).stream()
                .map(f -> f.getDish().getUid())
                .collect(Collectors.toSet());
    }
}
