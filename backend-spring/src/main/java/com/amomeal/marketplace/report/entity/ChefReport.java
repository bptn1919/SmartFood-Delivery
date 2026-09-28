package com.amomeal.marketplace.report.entity;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors ../../backend/report/models.py::ChefReport. Own BIGSERIAL id + unique uid (as Django).
 *
 * <p>The scalar {@code *Id}/{@code *Uid} columns are read-only duplicates of the FK columns so the
 * response mapper can read them after the transaction without touching lazy proxies
 * ({@code spring.jpa.open-in-view=false}); code that builds a report sets both.
 */
@Entity
@Table(name = "chef_report")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChefReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID uid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id")
    private CustomUser reporter;

    @Column(name = "reporter_id", insertable = false, updatable = false)
    private Long reporterId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chef_id", nullable = false)
    private CustomUser chef;

    @Column(name = "chef_id", insertable = false, updatable = false)
    private Long chefId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_uid")
    private Order order;

    @Column(name = "order_uid", insertable = false, updatable = false)
    private UUID orderUid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dish_uid")
    private Dish dish;

    @Column(name = "dish_uid", insertable = false, updatable = false)
    private UUID dishUid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evidence_uid")
    private Attachment evidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportCategory category;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(name = "credibility_weight", nullable = false)
    private double credibilityWeight = 1.0;

    /** String (not enum): Django stores whatever severity Gemini returned (max 10 chars). */
    @Column(name = "ai_severity", length = 10)
    private String aiSeverity;

    @Column(name = "ai_food_safety_risk")
    private Boolean aiFoodSafetyRisk;

    @Column(name = "ai_severity_reason", columnDefinition = "TEXT")
    private String aiSeverityReason;

    @Column(name = "ai_analyzed_at")
    private Instant aiAnalyzedAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ReportStatus status = ReportStatus.PENDING;

    @Column(name = "admin_note", columnDefinition = "TEXT")
    private String adminNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_id")
    private CustomUser reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Builder.Default
    @Column(nullable = false)
    private boolean deleted = false;

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
