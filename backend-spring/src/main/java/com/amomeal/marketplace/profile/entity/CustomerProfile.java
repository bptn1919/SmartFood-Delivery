package com.amomeal.marketplace.profile.entity;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.dish.entity.AllergyMode;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Mirrors ../../backend/profile/models.py::CustomerProfile. {@code dietMode}/
 * {@code dietLevel} are inputs the not-yet-ported `recommendation` module will
 * consume later (CLAUDE.md §7) — modeled faithfully here, no recommendation
 * logic built in this port. {@code allergyMode} reuses
 * {@link com.amomeal.marketplace.dish.entity.AllergyMode} (already declared by
 * `dish` for its {@code DishUserContext} seam) rather than redeclaring an
 * identical enum.
 *
 * <p>PORT-NOTE: Django's {@code CheckConstraint("diet_mode_level_consistency")}
 * (either both NONE, or both non-NONE) is ported as a real Postgres CHECK
 * constraint (V7 migration) rather than only application-level validation,
 * matching Django's own enforcement point. This reproduces a real Django quirk
 * on purpose: {@code CustomerService.update_customer_profile} only rejects a
 * diet_mode-without-diet_level combination when it can detect it from the
 * payload/current value (see that method's two explicit checks); a payload
 * that sets {@code diet_mode=NONE} alone while a non-NONE {@code diet_level}
 * already exists in the DB slips past both checks and instead fails at the
 * database with a constraint violation, surfacing as an uncaught 500
 * CONTACT_ADMIN_FOR_SUPPORT — exactly Django's behavior (an uncaught
 * `IntegrityError` there maps the same way), not fixed here.
 */
@Entity
@Table(name = "customer_profile")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private CustomUser user;

    @Column(columnDefinition = "text")
    private String bio;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attachment_uid")
    private Attachment avatar;

    @Builder.Default
    @Column(nullable = false)
    private int points = 0;

    @Builder.Default
    @Column(name = "is_onboarded", nullable = false)
    private boolean isOnboarded = false;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "diet_mode", nullable = false, length = 17)
    private DietMode dietMode = DietMode.NONE;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "diet_level", nullable = false, length = 16)
    private DietLevel dietLevel = DietLevel.NONE;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "allergy_mode", nullable = false, length = 16)
    private AllergyMode allergyMode = AllergyMode.WARN;
}
