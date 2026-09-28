package com.amomeal.marketplace.users.service;

import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the role/permission resolution ported from Django's Group→Permission
 * matrix ({@code users/management/commands/setup_permissions.py}, resolved
 * against {@code ../../auth_permission.json} — see PROGRESS.md "Part 1
 * findings"), plus the two {@code ModelBackend} behaviors that matrix is read
 * through (inactive → nothing, superuser → everything).
 */
class RolePermissionsTest {

    private static CustomUser user(UserRole... roles) {
        CustomUser user = CustomUser.builder().id(1L).username("u").email("u@x.test").build();
        for (UserRole role : roles) {
            user.addRole(role);
        }
        return user;
    }

    @Test
    void chefCanChangeADish_customerCanOnlyViewOne() {
        assertThat(RolePermissions.hasPerm(user(UserRole.CHEF), "dish.change_dish")).isTrue();
        assertThat(RolePermissions.hasPerm(user(UserRole.CHEF), "dish.view_dish")).isTrue();

        // The exact asymmetry dish's @require_object_permission relies on.
        assertThat(RolePermissions.hasPerm(user(UserRole.CUSTOMER), "dish.change_dish")).isFalse();
        assertThat(RolePermissions.hasPerm(user(UserRole.CUSTOMER), "dish.view_dish")).isTrue();
    }

    @Test
    void adminHoldsTheDishAndMenuPermissions_butNotChefsAttachmentOnes() {
        CustomUser admin = user(UserRole.ADMIN);
        assertThat(RolePermissions.hasPerm(admin, "dish.change_dish")).isTrue();
        assertThat(RolePermissions.hasPerm(admin, "dish.delete_dish")).isTrue();
        assertThat(RolePermissions.hasPerm(admin, "menu.delete_menu")).isTrue();
        assertThat(RolePermissions.hasPerm(admin, "order.delete_order")).isTrue();

        // Faithful to setup_permissions.py's own list: ADMIN's ID block omits
        // attachment (33-36) and profile (61-72), which CHEF/CUSTOMER do hold.
        assertThat(RolePermissions.hasPerm(admin, "attachment.add_attachment")).isFalse();
        assertThat(RolePermissions.hasPerm(user(UserRole.CHEF), "attachment.add_attachment")).isTrue();
        assertThat(RolePermissions.hasPerm(user(UserRole.CUSTOMER), "profile.change_customerprofile")).isTrue();
    }

    @Test
    void permissionsAreTheUnionAcrossEveryRoleHeld() {
        CustomUser both = user(UserRole.CUSTOMER, UserRole.CHEF);
        assertThat(RolePermissions.hasPerm(both, "cart.add_cartitem")).isTrue();   // CUSTOMER only
        assertThat(RolePermissions.hasPerm(both, "dish.change_dish")).isTrue();    // CHEF only
    }

    @Test
    void aUserWithNoRolesHasNoPermissions() {
        assertThat(RolePermissions.hasPerm(user(), "dish.view_dish")).isFalse();
        assertThat(RolePermissions.hasPerm(null, "dish.view_dish")).isFalse();
    }

    @Test
    void anInactiveUserHasNoPermissionsEvenAsSuperuser() {
        CustomUser inactive = user(UserRole.ADMIN);
        inactive.setActive(false);
        inactive.setSuperuser(true);
        assertThat(RolePermissions.hasPerm(inactive, "dish.change_dish")).isFalse();
    }

    @Test
    void anActiveSuperuserHasEveryPermission() {
        CustomUser superuser = user();
        superuser.setSuperuser(true);
        assertThat(RolePermissions.hasPerm(superuser, "dish.change_dish")).isTrue();
        assertThat(RolePermissions.hasPerm(superuser, "anything.at_all")).isTrue();
    }

    @Test
    void primaryRole_mirrorsGetUserRoleIncludingItsNoGroupsMeansAdminQuirk() {
        assertThat(user(UserRole.CHEF, UserRole.CUSTOMER).primaryRole()).isEqualTo(UserRole.CHEF);
        assertThat(user(UserRole.CUSTOMER).primaryRole()).isEqualTo(UserRole.CUSTOMER);
        // Django's utils/permissions/roles.py falls through to ADMIN — preserved quirk.
        assertThat(user().primaryRole()).isEqualTo(UserRole.ADMIN);
    }
}
