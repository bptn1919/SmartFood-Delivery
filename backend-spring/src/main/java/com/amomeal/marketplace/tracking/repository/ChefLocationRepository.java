package com.amomeal.marketplace.tracking.repository;

import com.amomeal.marketplace.tracking.entity.ChefLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChefLocationRepository extends JpaRepository<ChefLocation, Long> {

    Optional<ChefLocation> findByChefId(Long chefId);

    interface NearbyRow {
        Long getId();

        Double getDistanceKm();
    }

    /**
     * Django's haversine (spherical law of cosines, R = 6371) over rows with both coordinates, radius filter, nearest
     * first, top 10. PORT-NOTE: the acos argument is clamped to [-1, 1]; Django's raw SQL raises "input is out of
     * range" in Postgres when float rounding pushes it past 1 (e.g. searching exactly at a chef's position).
     */
    @Query(value = """
            SELECT t.id AS "id", t.distance_km AS "distanceKm" FROM (
              SELECT cl.id AS id,
                     6371 * acos(LEAST(1.0, GREATEST(-1.0,
                         cos(radians(CAST(:lat AS double precision))) * cos(radians(cl.latitude))
                           * cos(radians(cl.longitude) - radians(CAST(:lng AS double precision)))
                       + sin(radians(CAST(:lat AS double precision))) * sin(radians(cl.latitude))))) AS distance_km
              FROM chef_locations cl
              WHERE cl.latitude IS NOT NULL AND cl.longitude IS NOT NULL) t
            WHERE t.distance_km <= CAST(:radius AS double precision)
            ORDER BY t.distance_km
            LIMIT 10
            """, nativeQuery = true)
    List<NearbyRow> findNearby(@Param("lat") double lat, @Param("lng") double lng, @Param("radius") double radius);
}
