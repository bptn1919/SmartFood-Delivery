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

/** Mirrors ../../backend/report/models.py::ChefWarning — an email warning that never blocks orders. */
@Entity
@Table(name = "chef_warning")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChefWarning {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID uid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chef_id", nullable = false)
    private CustomUser chef;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warned_dish_uid")
    private Dish warnedDish;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "warning_type", nullable = false, length = 16)
    private WarningType warningType = WarningType.FOOD_QUALITY;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metricsSnapshot = new LinkedHashMap<>();

    @Builder.Default
    @Column(name = "email_sent", nullable = false)
    private boolean emailSent = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (uid == null) {
            uid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
