package com.amomeal.marketplace.profile.entity;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Mirrors ../../backend/profile/models.py::ChefProfile. Created by
 * {@code ProfileService.createChefProfile}, called both from this module's own
 * {@code POST /api/chef-profiles/} (idempotent update-if-exists, matching
 * Django's {@code get_or_create} + field-overlay behavior) and from the
 * CUSTOMER-to-CHEF upgrade flow ({@link com.amomeal.marketplace.profile.web.UpgradeToChefController}).
 */
@Entity
@Table(name = "chef_profile")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChefProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private CustomUser user;

    @Column(columnDefinition = "text")
    private String bio;

    @Column(columnDefinition = "text")
    private String specialty;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attachment_uid")
    private Attachment avatar;

    @Column(name = "kitchen_address", length = 255)
    private String kitchenAddress;

    @Column(name = "kitchen_street", length = 255)
    private String kitchenStreet;

    @Column(name = "kitchen_ward", length = 100)
    private String kitchenWard;

    @Column(name = "kitchen_district", length = 100)
    private String kitchenDistrict;

    @Column(name = "kitchen_city", length = 100)
    private String kitchenCity;

    @Column(name = "kitchen_latitude")
    private Double kitchenLatitude;

    @Column(name = "kitchen_longitude")
    private Double kitchenLongitude;

    @Builder.Default
    @Column(nullable = false)
    private double rating = 0.0;

    @Builder.Default
    @Column(name = "number_of_orders", nullable = false)
    private int numberOfOrders = 0;

    @Builder.Default
    @Column(name = "is_accepting_orders", nullable = false)
    private boolean isAcceptingOrders = true;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "suspension_level", nullable = false, length = 16)
    private ChefSuspensionLevel suspensionLevel = ChefSuspensionLevel.NONE;
}
