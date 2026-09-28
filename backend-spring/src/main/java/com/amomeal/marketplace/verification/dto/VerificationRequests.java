package com.amomeal.marketplace.verification.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Mirrors verification/schemas/requests.py. */
public final class VerificationRequests {

    private VerificationRequests() {
    }

    /** ĐKKD / ATTP: 1..10 pages. */
    public record AnalyzeDocumentRequest(
            @NotNull @Size(min = 1, message = "Cần ít nhất 1 ảnh.")
            @Size(max = 10, message = "Tối đa 10 ảnh mỗi lần upload.")
            List<UUID> attachmentUids) {
    }

    /** CCCD: exactly 2 images [front, back]. */
    public record AnalyzeCccdRequest(
            @NotNull @Size(min = 2, max = 2, message = "CCCD yêu cầu đúng 2 ảnh: ảnh mặt trước và ảnh mặt sau.")
            List<UUID> attachmentUids) {
    }

    /** Selfie: a single image. */
    public record AnalyzeSelfieRequest(@NotNull UUID attachmentUid) {
    }
}
