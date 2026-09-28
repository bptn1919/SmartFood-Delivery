package com.amomeal.marketplace.users.entity;

/**
 * Mirrors {@code ../backend/utils/enums.py::UserTypeEnum} — and, physically,
 * Django's {@code auth_group} rows created by
 * {@code users/management/commands/setup_permissions.py}
 * (CUSTOMER=id 1, CHEF=id 2, ADMIN=id 3).
 *
 * <p>In Django a "role" is a Group membership row, checked everywhere as
 * {@code user.groups.filter(name=UserTypeEnum.X).exists()}. Because the group
 * set is fixed and never edited at runtime (only that one management command
 * writes it), this port models membership as an enum collection on
 * {@link CustomUser} backed by the {@code app_user_role} join table, rather
 * than reproducing {@code auth_group}/{@code auth_user_groups} verbatim —
 * same semantics, idiomatic schema (CLAUDE.md §5).
 */
public enum UserRole {
    CUSTOMER,
    CHEF,
    ADMIN
}
