package com.amomeal.marketplace.review.dto;

import com.amomeal.marketplace.users.entity.CustomUser;

/** Mirrors ../../backend/review/schemas/responses.py::ReviewOwnerSchema. */
public record ReviewOwnerInfo(Long id, String email, String username, String fullName) {

    /** Django: {@code user.get_full_name()} — {@code (first_name + " " + last_name).strip()}. */
    static String getFullName(CustomUser user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName();
        String last = user.getLastName() == null ? "" : user.getLastName();
        return (first + " " + last).trim();
    }

    /**
     * Used by {@code ReviewResponse}/{@code ReviewDetailResponse} — Django:
     * {@code obj.owner.get_full_name() if obj.owner.get_full_name() else None}.
     */
    public static ReviewOwnerInfo of(CustomUser owner) {
        if (owner == null) {
            return null;
        }
        String fullName = getFullName(owner);
        return new ReviewOwnerInfo(owner.getId(), owner.getEmail(), owner.getUsername(),
                fullName.isEmpty() ? null : fullName);
    }

    /**
     * Used by {@code ReviewReplyResponse} only. PORT-NOTE: Django's
     * {@code ReviewReplyResponse.resolve_owner_info} is defined TWICE in
     * {@code review/schemas/responses.py} (a real copy-paste artifact, same class
     * of bug as {@code profile}'s duplicate {@code ChefPaymentController} —
     * Python keeps the LAST definition). The second (winning) definition is
     * {@code "full_name": obj.owner.get_full_name()} with NO
     * "or None"/"or email" fallback — unlike {@link #of}, an owner with blank
     * first/last name here serializes {@code full_name: ""}, not {@code null}.
     * Ported exactly: this is intentionally NOT the same as {@link #of}.
     */
    public static ReviewOwnerInfo ofReply(CustomUser owner) {
        if (owner == null) {
            return null;
        }
        return new ReviewOwnerInfo(owner.getId(), owner.getEmail(), owner.getUsername(), getFullName(owner));
    }
}
