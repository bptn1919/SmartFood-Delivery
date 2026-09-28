package com.amomeal.marketplace.recommendation.repository;

import com.amomeal.marketplace.recommendation.entity.DishRecipeSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DishRecipeSnapshotRepository extends JpaRepository<DishRecipeSnapshot, UUID> {
}
