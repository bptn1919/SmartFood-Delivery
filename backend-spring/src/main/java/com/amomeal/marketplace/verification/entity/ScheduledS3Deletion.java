package com.amomeal.marketplace.verification.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Mirrors ../../backend/verification/models.py::ScheduledS3Deletion. */
@Entity
@Table(name = "scheduled_s3_deletion")
@Getter
@Setter
@NoArgsConstructor
public class ScheduledS3Deletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attachment_uid", nullable = false)
    private UUID attachmentUid;

    @Column(name = "s3_bucket", nullable = false)
    private String s3Bucket;

    @Column(name = "s3_key", nullable = false, columnDefinition = "TEXT")
    private String s3Key;

    @Column(name = "delete_after", nullable = false)
    private Instant deleteAfter;

    @Column(name = "is_executed", nullable = false)
    private boolean isExecuted = false;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
