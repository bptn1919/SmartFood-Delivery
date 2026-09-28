package com.amomeal.marketplace.tracking.entity;

import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Mirrors ../../backend/tracking/models.py::ChefLocation (one row per chef, updated in place). */
@Entity
@Table(name = "chef_locations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChefLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chef_id", nullable = false, unique = true)
    private CustomUser chef;

    private Double latitude;
    private Double longitude;
    private Double heading;

    /** Django {@code auto_now}. */
    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    @PrePersist
    @PreUpdate
    void touch() {
        lastUpdated = Instant.now();
    }
}
