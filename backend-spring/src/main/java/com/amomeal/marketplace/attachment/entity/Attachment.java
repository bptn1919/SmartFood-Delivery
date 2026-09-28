package com.amomeal.marketplace.attachment.entity;

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
 * Mirrors ../../backend/attachment/models.py::Attachment — a shared file/image
 * record used by dish, certificate, profile, review, report, ingredient,
 * recommendation and verification (cross-app usage confirmed by grepping
 * ../../backend for {@code from attachment.services import} /
 * {@code from attachment.models import} / {@code from attachment.queries import}).
 * Every one of those callers only ever does one of: hold a nullable FK to a
 * completed Attachment row, or call
 * {@code AttachmentService.handleAttachment(uid)} to fetch+validate one before
 * linking it. Neither pattern requires those modules to exist yet for this
 * entity/service to be complete and correct on its own (attachment ports
 * before dish/ingredient/etc. per CLAUDE.md §7 port order).
 *
 * <p>{@code uploadToken} has no Django equivalent — see PORT-NOTE in
 * V2__init_attachment.sql and {@link com.amomeal.marketplace.attachment.service.AttachmentStorageService}.
 */
@Entity
@Table(name = "attachment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Attachment {

    @Id
    private UUID uid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 255)
    private AttachmentType type;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    @Column(name = "hashed_name", nullable = false)
    private String hashedName;

    /** Django IntegerField (32-bit) — ported as-is, not widened to long. */
    @Column(nullable = false)
    private Integer size;

    @Column(name = "content_type", nullable = false, length = 255)
    private String contentType;

    @Column(nullable = false, length = 255)
    private String bucket;

    @Column(nullable = false)
    private String directory;

    @Builder.Default
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = true;

    @Column(name = "public_url", nullable = false)
    private String publicUrl;

    /** PORT-NOTE: local-storage-only capability token, see class javadoc. */
    @Column(name = "upload_token", length = 64)
    private String uploadToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder.Default
    @Column(name = "is_file_deleted", nullable = false)
    private boolean isFileDeleted = false;

    @Builder.Default
    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Builder.Default
    @Column(name = "is_completed", nullable = false)
    private boolean isCompleted = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private CustomUser owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updater_id")
    private CustomUser updater;

    @PrePersist
    void onCreate() {
        if (uid == null) {
            uid = UUID.randomUUID();
        }
        if (type != null) {
            directory = type.directory();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        if (type != null) {
            directory = type.directory();
        }
        updatedAt = Instant.now();
    }
}
