package com.amomeal.marketplace.attachment.web;

import com.amomeal.marketplace.attachment.dto.GeneratePresignedUrlRequest;
import com.amomeal.marketplace.attachment.dto.GeneratePresignedUrlResponse;
import com.amomeal.marketplace.attachment.service.AttachmentService;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.UUID;

/**
 * Mirrors ../../backend/attachment/api.py::AttachmentController. Endpoint paths
 * and HTTP methods match exactly (cross-checked against
 * FE-admin/src/services/attachmentService.js, which calls
 * {@code POST /api/attachments/presigned-url} and
 * {@code PUT /api/attachments/{uid}/completed}).
 *
 * <p>{@code PUT /{uid}/upload} has NO Django equivalent — it only matters when
 * {@code app.storage.backend=local} (see
 * com.amomeal.marketplace.attachment.service.LocalAttachmentStorageService),
 * as this port's local filesystem substitute for "PUT the file straight to
 * S3". Under the default {@code s3} backend the client PUTs directly to the
 * real presigned S3 URL and never hits this endpoint at all. It's public
 * (see SecurityConfig — matched by upload-token possession instead of a
 * bearer token, the same self-authorizing-URL model S3 presigned URLs use).
 */
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    @PostMapping("/presigned-url")
    public GeneratePresignedUrlResponse getPresignedUrl(
            @AuthenticationPrincipal CustomUser user,
            @Valid @RequestBody GeneratePresignedUrlRequest request
    ) {
        return attachmentService.generatePresignedUrl(user.getId(), request);
    }

    @PutMapping("/{uid}/completed/{instanceUid}")
    public boolean completedUpload(
            @AuthenticationPrincipal CustomUser user,
            @PathVariable UUID uid,
            @PathVariable UUID instanceUid
    ) {
        return attachmentService.completedUpload(user.getId(), uid, instanceUid);
    }

    @PutMapping("/{uid}/completed")
    public boolean completedUploadWithoutInstance(
            @AuthenticationPrincipal CustomUser user,
            @PathVariable UUID uid
    ) {
        return attachmentService.completedUpload(user.getId(), uid, null);
    }

    @PutMapping("/{uid}/upload")
    public void upload(
            @PathVariable UUID uid,
            @RequestParam String token,
            HttpServletRequest request
    ) throws IOException {
        attachmentService.storeUploadedFile(uid, token, request.getInputStream(), request.getContentLengthLong());
    }
}
