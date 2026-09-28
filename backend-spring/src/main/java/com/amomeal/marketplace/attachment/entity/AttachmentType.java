package com.amomeal.marketplace.attachment.entity;

/**
 * Mirrors ../../backend/attachment/models.py::AttachmentType (Django TextChoices).
 * Enum constant names are the exact string values Django stores/serializes
 * (e.g. "CHEF_AVATAR") — do not rename without checking FE-admin/callers, this
 * value round-trips through JSON request/response bodies as-is.
 */
public enum AttachmentType {
    INGREDIENT,
    DISH,
    CERTIFICATE,
    CHEF_AVATAR,
    CUSTOMER_AVATAR,
    REVIEW,
    REPORT,
    OTHER,
    CHAT;

    /** Mirrors Attachment.save()'s {@code self.directory = self.type.lower()}. */
    public String directory() {
        return name().toLowerCase();
    }
}
