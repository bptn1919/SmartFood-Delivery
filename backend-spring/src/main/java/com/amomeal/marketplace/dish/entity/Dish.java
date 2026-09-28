package com.amomeal.marketplace.dish.entity;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.ingredient.service.RemoveAccents;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors ../../backend/dish/models.py::Dish (BaseModel + name/category/price/
 * status + location/owner/updater/attachment FKs + rating/score/suspension/
 * serving-size).
 *
 * <p>{@code nameNoAccent} is derived exactly as Django's {@code save()} override
 * does ({@code remove_accents(name)}) — reusing the already-ported
 * {@link RemoveAccents} from the `ingredient` module rather than re-declaring it.
 *
 * <p>Django's {@code clean()} also enforces "a dish's location must be a COUNTRY";
 * that check needs no DB access, but is applied in
 * {@link com.amomeal.marketplace.dish.service.DishService} where the location is
 * resolved (JPA lifecycle callbacks can't safely touch a lazy association).
 *
 * <p>FK cascade behavior mirrors Django's {@code on_delete=SET_NULL} for
 * location/owner/updater/attachment.
 */
@Entity
@Table(name = "dish")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Dish {

    @Id
    private UUID uid;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "name_no_accent", nullable = false, columnDefinition = "TEXT")
    private String nameNoAccent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DishCategory category;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DishStatus status = DishStatus.AVAILABLE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private DishLocation location;

    @Builder.Default
    @Column(nullable = false)
    private boolean deleted = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private CustomUser owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updater_id")
    private CustomUser updater;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attachment_uid")
    private Attachment attachment;

    @Builder.Default
    @Column(name = "avg_rating", nullable = false)
    private double avgRating = 0;

    @Builder.Default
    @Column(name = "final_score", nullable = false)
    private double finalScore = 0;

    @Builder.Default
    @Column(name = "is_suspended", nullable = false)
    private boolean suspended = false;

    /** Django: PositiveSmallIntegerField(default=1) — people per portion. */
    @Builder.Default
    @Column(name = "serving_size", nullable = false)
    private int servingSize = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (uid == null) {
            uid = UUID.randomUUID();
        }
        if (name != null) {
            nameNoAccent = RemoveAccents.apply(name);
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        if (name != null) {
            nameNoAccent = RemoveAccents.apply(name);
        }
        updatedAt = Instant.now();
    }
}
