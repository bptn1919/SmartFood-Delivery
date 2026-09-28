package com.amomeal.marketplace.report.entity;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Mirrors ../../backend/report/models.py::ChefSuspension (see {@link ChefReport} for the scalar-column pattern). */
@Entity
@Table(name = "chef_suspension")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChefSuspension {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID uid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chef_id", nullable = false)
    private CustomUser chef;

    @Column(name = "chef_id", insertable = false, updatable = false)
    private Long chefId;

    @Enumerated(EnumType.STRING)
    @Column(name = "suspension_type", nullable = false, length = 12)
    private SuspensionType suspensionType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "locked_dish_uid")
    private Dish lockedDish;

    @Column(name = "locked_dish_uid", insertable = false, updatable = false)
    private UUID lockedDishUid;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source", nullable = false, length = 8)
    private SuspensionTrigger triggerSource = SuspensionTrigger.SYSTEM;

    /** Snapshot metrics at trigger time (audit). */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_data", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> triggerData = new LinkedHashMap<>();

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private SuspensionStatus status = SuspensionStatus.ACTIVE;

    @Column(name = "appeal_text", columnDefinition = "TEXT")
    private String appealText;

    @Column(name = "appealed_at")
    private Instant appealedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lifted_by_id")
    private CustomUser liftedBy;

    @Column(name = "lifted_at")
    private Instant liftedAt;

    @Column(name = "lift_note", columnDefinition = "TEXT")
    private String liftNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (uid == null) {
            uid = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
