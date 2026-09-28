package com.amomeal.marketplace.attachment.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/attachments.py::AttachmentNotFound. */
public class AttachmentNotFoundException extends ApiException {
    public AttachmentNotFoundException() {
        super(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "Attachment not found");
    }

    /** Mirrors call sites like {@code raise AttachmentNotFound("File not found on S3")} — Django's
     * APIException(detail) sets only {@code detail}, message stays the class default. */
    public AttachmentNotFoundException(Object detail) {
        super(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "Attachment not found", detail);
    }
}
