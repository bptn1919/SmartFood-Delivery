package com.amomeal.marketplace.attachment.service;

import com.amomeal.marketplace.attachment.dto.GeneratePresignedUrlRequest;
import com.amomeal.marketplace.attachment.dto.GeneratePresignedUrlResponse;
import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.entity.AttachmentType;
import com.amomeal.marketplace.attachment.exception.AttachmentAlreadyCompletedException;
import com.amomeal.marketplace.attachment.exception.AttachmentIsNotCompletedException;
import com.amomeal.marketplace.attachment.exception.AttachmentNotFoundException;
import com.amomeal.marketplace.attachment.exception.InvalidUploadTokenException;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * Mirrors ../../backend/attachment/services.py::AttachmentService business logic
 * 1:1. Storage is pluggable ({@link AttachmentStorageService}) — real S3
 * ({@link S3AttachmentStorageService}) by default, matching Django's actual
 * behavior, with local filesystem storage as an opt-in (see PROGRESS.md).
 *
 * <p>Upload lifecycle (../../backend/exceptions/attachments.py):
 * <ol>
 *   <li>{@link #generatePresignedUrl} creates the DB row (is_completed=false) and
 *       hands back an upload URL.</li>
 *   <li>Client PUTs the raw file bytes to that URL — under S3 (the default),
 *       directly to S3; under the local-storage opt-in, to
 *       {@code storeUploadedFile} via the controller's {@code PUT /{uid}/upload}
 *       endpoint.</li>
 *   <li>{@link #completedUpload} verifies the file actually landed in storage
 *       and flips {@code is_completed=true}. Calling it twice throws
 *       {@link AttachmentAlreadyCompletedException}.</li>
 *   <li>{@link #handleAttachment} is what every OTHER module (dish, ingredient,
 *       certificate, review, profile, recommendation, verification — confirmed
 *       by grepping ../../backend for {@code attachment_service.handle_attachment})
 *       calls before linking an attachment uid to its own entity; it throws
 *       {@link AttachmentIsNotCompletedException} if step 3 never happened.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final AttachmentRepository attachmentRepository;
    private final CustomUserRepository customUserRepository;
    private final AttachmentStorageService storageService;

    @Transactional
    public GeneratePresignedUrlResponse generatePresignedUrl(Long userId, GeneratePresignedUrlRequest request) {
        CustomUser owner = userId == null ? null : customUserRepository.getReferenceById(userId);

        Attachment attachment = Attachment.builder()
                .uid(UUID.randomUUID()) // assigned up front (not left to @PrePersist): the upload URL
                                         // built below (before save()) must embed the real uid.
                .type(request.attachmentType())
                .originalName(request.fileName())
                .hashedName(AttachmentNaming.generateHashedName(request.fileName()))
                .size(request.fileSize())
                .contentType(Objects.requireNonNullElse(AttachmentNaming.getContentType(request.fileName()), ""))
                .owner(owner)
                .bucket(storageService.bucketName())
                .isPublic(true)
                .build();

        // directory is derived from type in @PrePersist, but buildPublicUrl below needs it now.
        attachment.setDirectory(request.attachmentType().directory());
        attachment.setPublicUrl(storageService.buildPublicUrl(attachment));

        String uploadUrl = storageService.generateUploadUrl(attachment); // also assigns uploadToken
        attachment = attachmentRepository.save(attachment);

        return new GeneratePresignedUrlResponse(attachment.getUid(), uploadUrl);
    }

    /** Local-storage-only entry point backing {@code PUT /api/attachments/{uid}/upload} — no Django equivalent
     *  (irrelevant when app.storage.backend=s3, where the client PUTs straight to S3 instead). */
    @Transactional
    public void storeUploadedFile(UUID uid, String token, InputStream content, long contentLength) {
        Attachment attachment = attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(uid)
                .orElseThrow(AttachmentNotFoundException::new);
        if (token == null || attachment.getUploadToken() == null || !attachment.getUploadToken().equals(token)) {
            throw new InvalidUploadTokenException();
        }
        try {
            storageService.storeFile(attachment, content, contentLength);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store attachment file", e);
        }
    }

    @Transactional
    public boolean completedUpload(Long userId, UUID uid, UUID instanceUid) {
        Attachment attachment = attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(uid)
                .orElseThrow(AttachmentNotFoundException::new);

        if (attachment.isCompleted()) {
            throw new AttachmentAlreadyCompletedException();
        }

        if (!storageService.fileExists(attachment)) {
            throw new AttachmentNotFoundException("File not found in storage");
        }

        if (instanceUid != null && attachment.getType() == AttachmentType.DISH) {
            // PORT-NOTE: Django links a DISH-type attachment to a Dish row here via
            // DishORM.add_attachment (../../backend/attachment/services.py::completed_upload).
            // The `dish` module hasn't been ported to Spring yet (attachment ports first,
            // per CLAUDE.md §7 recommended order) — deferred, not silently dropped. Wire this
            // up when `dish` is ported: look up the Dish by instanceUid (404 DishNotFoundException
            // if missing, mirroring Django), then set dish.attachment = this attachment.
            log.warn("completedUpload({}) requested dish-attachment link to instanceUid={}, but the dish "
                    + "module isn't ported yet — skipping the link (see PORT-NOTE in AttachmentService).",
                    uid, instanceUid);
        }

        attachment.setCompleted(true);
        attachment.setUpdater(userId == null ? null : customUserRepository.getReferenceById(userId));
        attachmentRepository.save(attachment);
        return true;
    }

    /** Mirrors AttachmentService.handle_attachment — the call every other module makes before linking. */
    @Transactional(readOnly = true)
    public Attachment handleAttachment(UUID uid) {
        Attachment attachment = attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(uid)
                .orElseThrow(AttachmentNotFoundException::new);
        if (!attachment.isCompleted()) {
            throw new AttachmentIsNotCompletedException(
                    "Attachment upload chưa hoàn tất. Vui lòng gọi PUT /attachments/{uid}/completed trước.");
        }
        return attachment;
    }

    /**
     * Mirrors AttachmentService.post_file — a direct (non-presigned) upload used
     * internally (../../backend/ingredient/admin.py, Django-admin only, not exposed
     * via api.py). PORT-NOTE: Django's post_file never sets is_completed=true either
     * (ported faithfully, not fixed) — a caller relying on handleAttachment()
     * afterward would still get AttachmentIsNotCompletedException, same as Django.
     * Not wired to any controller yet; kept as a reusable service method for future
     * module ports (ingredient) that need it.
     */
    @Transactional
    public Attachment uploadFileDirectly(Long userId, String fileName, byte[] content, AttachmentType type) {
        CustomUser owner = userId == null ? null : customUserRepository.getReferenceById(userId);

        Attachment attachment = Attachment.builder()
                .type(type)
                .originalName(fileName)
                .hashedName(AttachmentNaming.generateHashedName(fileName))
                .size(content.length)
                .contentType(Objects.requireNonNullElse(AttachmentNaming.getContentType(fileName), ""))
                .owner(owner)
                .bucket(storageService.bucketName())
                .isPublic(true)
                .build();
        attachment.setDirectory(type.directory());
        attachment.setPublicUrl(storageService.buildPublicUrl(attachment));
        attachment = attachmentRepository.save(attachment);

        try {
            storageService.storeFile(attachment, new java.io.ByteArrayInputStream(content), content.length);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store attachment file", e);
        }
        return attachment;
    }
}
