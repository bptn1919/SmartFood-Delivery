package com.amomeal.marketplace.dish.repository;

import com.amomeal.marketplace.dish.entity.DishAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DishAliasRepository extends JpaRepository<DishAlias, UUID> {

    /**
     * Mirrors DishSearchService.retrieve_candidates stage 2 (exact alias match).
     * PORT-NOTE: Django's filter here is {@code alias_name_no_accent__iexact} with
     * no {@code dish__deleted=False} guard — a deleted dish CAN come back from this
     * stage. Preserved as-is (the fuzzy stages below do filter deleted dishes out).
     */
    @Query("SELECT a FROM DishAlias a JOIN FETCH a.dish d LEFT JOIN FETCH d.attachment "
            + "WHERE LOWER(a.aliasNameNoAccent) = LOWER(:alias)")
    List<DishAlias> findExactByAliasNoAccent(@Param("alias") String alias);

    /** Mirrors DishSearchService.retrieve_candidates stage 4 (fuzzy over all aliases of live dishes). */
    @Query("SELECT a FROM DishAlias a JOIN FETCH a.dish d LEFT JOIN FETCH d.attachment WHERE d.deleted = false")
    List<DishAlias> findAllOfLiveDishes();

    List<DishAlias> findAllByDishUid(UUID dishUid);
}
