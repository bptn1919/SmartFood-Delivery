package com.amomeal.marketplace.attachment.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * NOT present in Django (../../backend/exceptions/attachments.py has no equivalent) —
 * this is purely a consequence of substituting local filesystem storage for S3's
 * presigned-URL signature check (see AttachmentStorageService PORT-NOTE). Flagged
 * in PROGRESS.md.
 */
public class InvalidUploadTokenException extends ApiException {
    public InvalidUploadTokenException() {
        super(HttpStatus.FORBIDDEN, "INVALID_UPLOAD_TOKEN", "Invalid or missing upload token");
    }
}
