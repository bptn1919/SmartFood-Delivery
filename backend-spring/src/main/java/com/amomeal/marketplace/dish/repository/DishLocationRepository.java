package com.amomeal.marketplace.dish.repository;

import com.amomeal.marketplace.dish.entity.DishLocation;
import com.amomeal.marketplace.dish.entity.DishLocationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DishLocationRepository extends JpaRepository<DishLocation, Long> {

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    boolean existsByParent(DishLocation parent);

    List<DishLocation> findAllByOrderByNameAsc();

    List<DishLocation> findAllByParentIsNullOrderByNameAsc();

    List<DishLocation> findAllByParentIsNullAndTypeOrderByNameAsc(DishLocationType type);

    List<DishLocation> findAllByParentIdOrderByNameAsc(Long parentId);

    List<DishLocation> findAllByParentIdAndTypeOrderByNameAsc(Long parentId, DishLocationType type);

    /** Mirrors DishORM.get_country_locations. */
    List<DishLocation> findAllByTypeOrderByNameAsc(DishLocationType type);
}
