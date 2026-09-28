package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.dish.entity.AllergyMode;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Default {@link DishUserContext} until `profile` is ported — mirrors Djangos
 * own no-CustomerProfile-row behavior (allergy mode WARN, no favourites).
 * Replaced by `profile`'s own @Primary implementation once that module lands.
 *
 * <p><b>How this gets replaced:</b> {@code @ConditionalOnMissingBean} is NOT used
 * here on purpose - it is only evaluated for auto-configuration {@code @Bean}
 * methods, never for component-scanned {@code @Component}s (using it that way
 * silently never fires, and broke this application context once already). The
 * module that takes ownership therefore annotates its own implementation
 * {@code @Primary}, and this default quietly steps aside.
 */
@Component
public class NoProfileDishUserContext implements DishUserContext {

    @Override
    public AllergyMode allergyMode(Long userId) {
        return AllergyMode.WARN;
    }

    @Override
    public Set<UUID> favoriteDishUids(Long userId) {
        return Set.of();
    }
}
