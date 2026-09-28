package com.amomeal.marketplace.attachment.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/attachments.py::AttachmentAlreadyCompleted. */
public class AttachmentAlreadyCompletedException extends ApiException {
    public AttachmentAlreadyCompletedException() {
        super(HttpStatus.BAD_REQUEST, "ATTACHMENT_ALREADY_COMPLETED", "Attachment already completed");
    }
}
