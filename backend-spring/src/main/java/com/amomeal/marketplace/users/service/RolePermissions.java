package com.amomeal.marketplace.users.service;

import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port of Django's Group→Permission matrix, i.e. what
 * {@code users/management/commands/setup_permissions.py} writes into
 * {@code auth_group_permissions}, and therefore what
 * {@code request.user.has_perm("<app_label>.<codename>")} answers at runtime
 * (Django's {@code ModelBackend} unions the permissions of every group the user
 * belongs to, plus any direct user_permissions — this project grants none
 * directly, only through groups).
 *
 * <p><b>Why a constant instead of tables.</b> The matrix is not runtime data in
 * Django either: nothing in the API layer ever writes {@code auth_permission} or
 * {@code auth_group_permissions}; a single management command seeds them from
 * hardcoded numeric ID lists. Reproducing two Django admin tables to hold a
 * constant would be schema archaeology for no behavioral gain (CLAUDE.md §5).
 *
 * <p><b>Where the IDs came from.</b> {@code setup_permissions.py} lists raw
 * permission IDs with only partial comments. They were resolved against
 * {@code ../../auth_permission.json} (the repo-root export of Django's
 * {@code auth_permission} table — an unused data file, referenced by no code,
 * but an accurate ID→codename map), and the {@code app_label} prefix from the
 * app each model actually lives in ({@code order.models::Checkout},
 * {@code cart.models::CartItem}, {@code profile.models::ChefProfile}, ...).
 *
 * <p>PORT-NOTE: ADMIN genuinely does <em>not</em> hold the attachment or profile
 * permissions CHEF holds — that asymmetry is in Django's own list, not a
 * transcription slip. It is harmless today because the only permissions any
 * endpoint actually checks are {@code dish.*} and {@code menu.*}.
 */
public final class RolePermissions {

    private RolePermissions() {
    }

    // ids 64-72 + 76-80 + 81,82,84 + 85,88,89,92, plus the view_* set at 40/44/48/52/56/60
    private static final Set<String> CUSTOMER_PERMISSIONS = Set.copyOf(List.of(
            "dish.view_dishavailability",   // 40
            "dish.view_dishingredient",     // 44
            "dish.view_dish",               // 48
            "ingredient.view_ingredient",   // 52
            "menu.view_menu",               // 56
            "menu.view_menudish",           // 60
            "profile.view_chefprofile",     // 64
            "profile.add_customeraddress",  // 65
            "profile.change_customeraddress", // 66
            "profile.delete_customeraddress", // 67
            "profile.view_customeraddress", // 68
            "profile.add_customerprofile",  // 69
            "profile.change_customerprofile", // 70
            "profile.delete_customerprofile", // 71
            "profile.view_customerprofile", // 72
            "cart.view_cart",               // 76
            "cart.add_cartitem",            // 77
            "cart.change_cartitem",         // 78
            "cart.delete_cartitem",         // 79
            "cart.view_cartitem",           // 80
            "order.add_checkout",           // 81
            "order.change_checkout",        // 82
            "order.view_checkout",          // 84
            "order.add_order",              // 85
            "order.view_order",             // 88
            "order.add_orderitem",          // 89
            "order.view_orderitem"          // 92
    ));

    // ids 33-64 (contiguous) + 86 + 88
    private static final Set<String> CHEF_PERMISSIONS = Set.copyOf(List.of(
            "attachment.add_attachment", "attachment.change_attachment",
            "attachment.delete_attachment", "attachment.view_attachment",           // 33-36
            "dish.add_dishavailability", "dish.change_dishavailability",
            "dish.delete_dishavailability", "dish.view_dishavailability",           // 37-40
            "dish.add_dishingredient", "dish.change_dishingredient",
            "dish.delete_dishingredient", "dish.view_dishingredient",               // 41-44
            "dish.add_dish", "dish.change_dish", "dish.delete_dish", "dish.view_dish", // 45-48
            "ingredient.add_ingredient", "ingredient.change_ingredient",
            "ingredient.delete_ingredient", "ingredient.view_ingredient",           // 49-52
            "menu.add_menu", "menu.change_menu", "menu.delete_menu", "menu.view_menu", // 53-56
            "menu.add_menudish", "menu.change_menudish",
            "menu.delete_menudish", "menu.view_menudish",                           // 57-60
            "profile.add_chefprofile", "profile.change_chefprofile",
            "profile.delete_chefprofile", "profile.view_chefprofile",               // 61-64
            "order.change_order",                                                   // 86
            "order.view_order"                                                      // 88
    ));

    // ids 37-60 + 81-88
    private static final Set<String> ADMIN_PERMISSIONS = Set.copyOf(List.of(
            "dish.add_dishavailability", "dish.change_dishavailability",
            "dish.delete_dishavailability", "dish.view_dishavailability",           // 37-40
            "dish.add_dishingredient", "dish.change_dishingredient",
            "dish.delete_dishingredient", "dish.view_dishingredient",               // 41-44
            "dish.add_dish", "dish.change_dish", "dish.delete_dish", "dish.view_dish", // 45-48
            "ingredient.add_ingredient", "ingredient.change_ingredient",
            "ingredient.delete_ingredient", "ingredient.view_ingredient",           // 49-52
            "menu.add_menu", "menu.change_menu", "menu.delete_menu", "menu.view_menu", // 53-56
            "menu.add_menudish", "menu.change_menudish",
            "menu.delete_menudish", "menu.view_menudish",                           // 57-60
            "order.add_checkout", "order.change_checkout",
            "order.delete_checkout", "order.view_checkout",                         // 81-84
            "order.add_order", "order.change_order",
            "order.delete_order", "order.view_order"                                // 85-88
    ));

    private static final Map<UserRole, Set<String>> BY_ROLE = new EnumMap<>(Map.of(
            UserRole.CUSTOMER, CUSTOMER_PERMISSIONS,
            UserRole.CHEF, CHEF_PERMISSIONS,
            UserRole.ADMIN, ADMIN_PERMISSIONS));

    public static Set<String> forRole(UserRole role) {
        return BY_ROLE.getOrDefault(role, Set.of());
    }

    /**
     * Django: {@code user.has_perm("<app_label>.<codename>")} via
     * {@code ModelBackend}. Two behaviors of that backend are reproduced here
     * and matter:
     * <ul>
     *   <li>an <b>inactive</b> user has NO permissions at all, regardless of groups;</li>
     *   <li>an active <b>superuser</b> has EVERY permission, short-circuiting the
     *       group lookup entirely (this is the only place {@code is_superuser}
     *       influences authorization anywhere in the Django project).</li>
     * </ul>
     */
    public static boolean hasPerm(CustomUser user, String permission) {
        if (user == null || !user.isActive()) {
            return false;
        }
        if (user.isSuperuser()) {
            return true;
        }
        for (UserRole role : user.effectiveRoles()) {
            if (forRole(role).contains(permission)) {
                return true;
            }
        }
        return false;
    }
}
