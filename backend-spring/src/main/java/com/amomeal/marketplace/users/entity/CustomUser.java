package com.amomeal.marketplace.users.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Mirrors ../backend/users/models.py::CustomUser (Django AbstractUser +
 * phone_number, email unique, USERNAME_FIELD="email"). Login is by email;
 * username is kept as a separate required field, matching Django.
 *
 * Roles: Django's role system is auth Group membership (group names CUSTOMER /
 * CHEF / ADMIN, see {@link UserRole} and PROGRESS.md "Part 1 findings"), which
 * this port models as the {@code app_user_role} join table mapped here as an
 * {@code @ElementCollection}. {@code isStaff}/{@code isSuperuser} are kept
 * because Django's AbstractUser has them, but they are NOT the role signal —
 * {@code is_staff} is only ever read as a secondary OR fallback in
 * {@code voucher/services}, and {@code is_superuser} only matters through
 * Django's {@code ModelBackend.has_perm} blanket grant (ported in
 * {@code users/service/RolePermissions}).
 *
 * <p>The collection is EAGER on purpose: {@code JwtAuthenticationFilter} loads
 * the user outside any transaction on every request and immediately needs the
 * roles to build the granted authorities; LAZY here means a guaranteed
 * LazyInitializationException. The set is at most 3 small enum rows.
 */
@Entity
@Table(name = "app_user", uniqueConstraints = {
        @UniqueConstraint(name = "uk_app_user_username", columnNames = "username"),
        @UniqueConstraint(name = "uk_app_user_email", columnNames = "email")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String username;

    @Column(nullable = false, length = 254)
    private String email;

    /** Argon2id-encoded hash (Spring Security {@code Argon2PasswordEncoder}). */
    @Column(nullable = false)
    private String password;

    @Column(length = 15)
    private String phoneNumber;

    @Column(length = 150)
    private String firstName;

    @Column(length = 150)
    private String lastName;

    @Builder.Default
    @Column(nullable = false)
    private boolean isStaff = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean isActive = true;

    @Builder.Default
    @Column(nullable = false)
    private boolean isSuperuser = false;

    @Builder.Default
    @Column(nullable = false)
    private Instant dateJoined = Instant.now();

    private Instant lastLogin;

    /**
     * Django: {@code auth_user_groups} — the user's role memberships. Ported as
     * a join table owned by this module because Django assigns/removes them
     * from {@code users/queries.py}, not from {@code profile}.
     */
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "app_user_role",
            joinColumns = @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_app_user_role_user")))
    @Column(name = "role", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Set<UserRole> roles = new LinkedHashSet<>();

    /** Django: {@code user.groups.filter(name=role).exists()}. */
    public boolean hasRole(UserRole role) {
        return roles != null && roles.contains(role);
    }

    public boolean isAdmin() {
        return hasRole(UserRole.ADMIN);
    }

    public boolean isChef() {
        return hasRole(UserRole.CHEF);
    }

    public void addRole(UserRole role) {
        if (roles == null) {
            roles = new LinkedHashSet<>();
        }
        roles.add(role);
    }

    public void removeRole(UserRole role) {
        if (roles != null) {
            roles.remove(role);
        }
    }

    /**
     * Django: {@code utils/permissions/roles.py::get_user_role} — CHEF wins over
     * CUSTOMER, and a user in NEITHER group reads as ADMIN. That last branch is a
     * quirk (an account with no groups is not an administrator in any meaningful
     * sense), preserved verbatim per CLAUDE.md §0.1. Note this helper is only used
     * for *reporting* a user's role in Django, never for gating — every gate goes
     * through an explicit group check, i.e. {@link #hasRole}.
     */
    public UserRole primaryRole() {
        if (hasRole(UserRole.CHEF)) {
            return UserRole.CHEF;
        }
        if (hasRole(UserRole.CUSTOMER)) {
            return UserRole.CUSTOMER;
        }
        return UserRole.ADMIN;
    }

    public Set<UserRole> effectiveRoles() {
        return roles == null || roles.isEmpty() ? EnumSet.noneOf(UserRole.class) : EnumSet.copyOf(roles);
    }
}
