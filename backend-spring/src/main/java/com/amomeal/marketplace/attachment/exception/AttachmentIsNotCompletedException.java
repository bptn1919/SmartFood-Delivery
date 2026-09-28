package com.amomeal.marketplace.attachment.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/attachments.py::AttachmentIsNotCompleted.
 * Vietnamese message text is user-facing FE copy — kept verbatim, not translated
 * (CLAUDE.md §4).
 */
public class AttachmentIsNotCompletedException extends ApiException {

    private static final String MESSAGE =
            "Attachment upload chưa hoàn tất. Vui lòng gọi PUT /attachments/{uid}/completed trước.";

    public AttachmentIsNotCompletedException() {
        super(HttpStatus.BAD_REQUEST, "ATTACHMENT_IS_NOT_COMPLETED", MESSAGE);
    }

    /** Mirrors AttachmentService.handle_attachment passing the same text again as `detail`. */
    public AttachmentIsNotCompletedException(Object detail) {
        super(HttpStatus.BAD_REQUEST, "ATTACHMENT_IS_NOT_COMPLETED", MESSAGE, detail);
    }
}
