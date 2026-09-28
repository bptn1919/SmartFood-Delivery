package com.amomeal.marketplace.menu.web;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.entity.AttachmentType;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification of the menu module through the full stack (real
 * filter chain, real Postgres via the shared {@link TestcontainersConfiguration}),
 * mirroring ../../backend/menu/api.py. Modeled on
 * {@code dish.web.DishControllerTest}'s pattern: register through the real
 * auth endpoint, then grant the account its Django role (CHEF/ADMIN/CUSTOMER —
 * {@code CustomUser.roles}, the port of Django auth Group membership) via the
 * repository.
 *
 * <p>Covers real role-based authorization: a CUSTOMER-role token must be
 * rejected from every CHEF-only endpoint (401, via Spring Security's
 * {@code AccessDeniedException} mapping, CLAUDE.md §4), not merely tolerated
 * as "any authenticated user" — the mistake this project has made twice before
 * (see PROGRESS.md).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MenuControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomUserRepository customUserRepository;
    @Autowired
    private AttachmentRepository attachmentRepository;

    // ===================================================================
    // Fixtures
    // ===================================================================

    private record Account(String token, Long userId) {
    }

    private Account register(String prefix, UserRole role) throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        String email = prefix + "+" + nonce + "@amomeal.test";
        String body = """
                {"username":"%s-%s","email":"%s","password":"correct-horse-battery","phone_number":"0900000006"}
                """.formatted(prefix, nonce, email);
        String json = mockMvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        CustomUser user = customUserRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.getRoles().clear();
        user.addRole(role);
        customUserRepository.save(user);
        return new Account(extractField(json, "access_token"), user.getId());
    }

    private UUID completedAttachment() {
        Attachment attachment = Attachment.builder()
                .type(AttachmentType.DISH)
                .originalName("mon.jpg")
                .hashedName("mon-" + UUID.randomUUID() + ".jpg")
                .size(1024)
                .contentType("image/jpeg")
                .bucket("amomeal-test-bucket")
                .directory("dish")
                .publicUrl("https://cdn.example.test/dish/mon.jpg")
                .isCompleted(true)
                .build();
        return attachmentRepository.save(attachment).getUid();
    }

    private String createDish(String chefToken, String name) throws Exception {
        String body = """
                {"name":"%s","category":"FOOD","price":40000,"attachment_uid":"%s"}
                """.formatted(name, completedAttachment());
        String json = mockMvc.perform(post("/api/dishes/")
                        .header("Authorization", "Bearer " + chefToken)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extractField(json, "uid");
    }

    private String createMenu(String chefToken, String name, String menuStatus) throws Exception {
        String body = """
                {"name":"%s","description":"mô tả","status":"%s"}
                """.formatted(name, menuStatus);
        String json = mockMvc.perform(post("/api/menus")
                        .header("Authorization", "Bearer " + chefToken)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extractField(json, "uid");
    }

    private static String extractField(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) {
            throw new IllegalStateException("field " + field + " not found in: " + json);
        }
        start += needle.length();
        return json.substring(start, json.indexOf('"', start));
    }

    // ===================================================================
    // create / role gating
    // ===================================================================

    @Test
    void createMenu_asChef_succeeds_andCustomerAndAnonymousAreRejected() throws Exception {
        Account chef = register("chef-create", UserRole.CHEF);
        Account customer = register("customer-create", UserRole.CUSTOMER);
        String nonce = String.valueOf(System.nanoTime());

        mockMvc.perform(post("/api/menus")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("{\"name\":\"Combo trưa " + nonce + "\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Combo trưa " + nonce))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.chef").value(chef.userId()));

        // A CUSTOMER-role token must be REJECTED, not merely tolerated.
        mockMvc.perform(post("/api/menus")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("{\"name\":\"Should fail\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/menus")
                        .contentType("application/json")
                        .content("{\"name\":\"Anon\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ===================================================================
    // mine / chef listing
    // ===================================================================

    @Test
    void getMyMenus_emptyIs404_thenPopulatedListsIt_andCustomerIsRejected() throws Exception {
        Account chef = register("chef-mine", UserRole.CHEF);
        Account customer = register("customer-mine", UserRole.CUSTOMER);

        mockMvc.perform(get("/api/menus/mine").header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("MENU_DOES_NOT_EXIST"));

        createMenu(chef.token(), "Menu A " + System.nanoTime(), "ACTIVE");
        createMenu(chef.token(), "Menu B " + System.nanoTime(), "DRAFT");

        mockMvc.perform(get("/api/menus/mine").header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get("/api/menus/mine").header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getAllMenusOfChef_onlyActiveNonDeleted_andDoesNotThrowOnEmpty() throws Exception {
        Account chef = register("chef-public", UserRole.CHEF);
        Account customer = register("customer-public", UserRole.CUSTOMER);
        String activeUid = createMenu(chef.token(), "Public active " + System.nanoTime(), "ACTIVE");
        createMenu(chef.token(), "Public draft " + System.nanoTime(), "DRAFT");

        mockMvc.perform(get("/api/menus/chef/" + chef.userId())
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].uid").value(activeUid));

        // Empty is a plain empty array here, unlike /mine.
        Account otherChef = register("chef-nomenus", UserRole.CHEF);
        mockMvc.perform(get("/api/menus/chef/" + otherChef.userId())
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ===================================================================
    // get_menu / get_all_dishes_in_menu — the "deleted is ignored" quirk
    // ===================================================================

    @Test
    void getMenu_draftIs404_activeIsVisible_andRemainsVisibleAfterSoftDelete() throws Exception {
        Account chef = register("chef-detail", UserRole.CHEF);
        String draftUid = createMenu(chef.token(), "Draft menu " + System.nanoTime(), "DRAFT");
        String activeUid = createMenu(chef.token(), "Active menu " + System.nanoTime(), "ACTIVE");

        mockMvc.perform(get("/api/menus/" + draftUid).header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("MENU_DOES_NOT_EXIST"));

        mockMvc.perform(get("/api/menus/" + activeUid).header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uid").value(activeUid));

        // Soft-delete it, then confirm the "deleted is ignored here" PORT-NOTE'd quirk:
        // it disappears from /mine but the public detail endpoint still serves it.
        mockMvc.perform(put("/api/menus/" + activeUid + "/deleted")
                        .header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/api/menus/" + activeUid).header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uid").value(activeUid));
    }

    @Test
    void getAllDishesInMenu_returnsOnlyActiveLinks_withHardcodedDecorationDefaults() throws Exception {
        Account chef = register("chef-dishesinmenu", UserRole.CHEF);
        String menuUid = createMenu(chef.token(), "Set món " + System.nanoTime(), "ACTIVE");
        String dishUid = createDish(chef.token(), "Phở đặc biệt " + System.nanoTime());

        mockMvc.perform(post("/api/menus/" + menuUid + "/add-dish")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("{\"dish_uid\":\"" + dishUid + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/menus/" + menuUid + "/dishes").header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].uid").value(dishUid))
                .andExpect(jsonPath("$.data[0].is_favorite").value(false))
                .andExpect(jsonPath("$.data[0].sold_count").value(0))
                .andExpect(jsonPath("$.data[0].in_stock").value(0));
    }

    // ===================================================================
    // update / soft-delete — has_perm + strict ownership
    // ===================================================================

    @Test
    void updateMenu_ownerSucceeds_strangerAndAdminAreDenied() throws Exception {
        Account owner = register("chef-upd-owner", UserRole.CHEF);
        Account stranger = register("chef-upd-stranger", UserRole.CHEF);
        Account admin = register("admin-upd", UserRole.ADMIN);
        String uid = createMenu(owner.token(), "Trước khi sửa " + System.nanoTime(), "ACTIVE");

        mockMvc.perform(put("/api/menus/" + uid)
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType("application/json")
                        .content("{\"name\":\"Sau khi sửa\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Sau khi sửa"));

        mockMvc.perform(put("/api/menus/" + uid)
                        .header("Authorization", "Bearer " + stranger.token())
                        .contentType("application/json")
                        .content("{\"name\":\"hijack\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message_code").value("PERMISSION_DENIED"));

        // PORT-NOTE (real quirk): an ADMIN who is not the owning chef is ALSO
        // denied here — Django's service re-check is strict, no ADMIN bypass.
        mockMvc.perform(put("/api/menus/" + uid)
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType("application/json")
                        .content("{\"name\":\"admin hijack\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message_code").value("PERMISSION_DENIED"));
    }

    // ===================================================================
    // restore — the dead-code bug
    // ===================================================================

    @Test
    void restoreMenu_canNeverActuallySucceed() throws Exception {
        Account chef = register("chef-restore", UserRole.CHEF);
        String uid = createMenu(chef.token(), "Sẽ không phục hồi được " + System.nanoTime(), "ACTIVE");

        // A live (non-deleted) menu: the "not deleted" check always fires.
        mockMvc.perform(put("/api/menus/" + uid + "/restore").header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message_code").value("MENU_IS_NOT_DELETED"));

        mockMvc.perform(put("/api/menus/" + uid + "/deleted").header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk());

        // A genuinely deleted menu: the authorization pre-check's own
        // deleted=False filter 404s before the restore logic is ever reached.
        mockMvc.perform(put("/api/menus/" + uid + "/restore").header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("MENU_DOES_NOT_EXIST"));
    }

    // ===================================================================
    // hard delete — ADMIN only
    // ===================================================================

    @Test
    void hardDeleteMenu_requiresAdminRole() throws Exception {
        Account chef = register("chef-hard", UserRole.CHEF);
        Account admin = register("admin-hard", UserRole.ADMIN);
        String uid = createMenu(chef.token(), "Sẽ bị xóa cứng " + System.nanoTime(), "ACTIVE");

        mockMvc.perform(delete("/api/menus/" + uid).header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/menus/" + uid).header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/api/menus/" + uid).header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isNotFound());
    }

    // ===================================================================
    // add-dish — the one endpoint where ADMIN (non-owner) can succeed
    // ===================================================================

    @Test
    void addDishToMenu_ownerSucceeds_thenDuplicateIsRejected() throws Exception {
        Account chef = register("chef-adddish", UserRole.CHEF);
        String menuUid = createMenu(chef.token(), "Menu thêm món " + System.nanoTime(), "ACTIVE");
        String dishUid = createDish(chef.token(), "Bún chả " + System.nanoTime());

        mockMvc.perform(post("/api/menus/" + menuUid + "/add-dish")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("{\"dish_uid\":\"" + dishUid + "\",\"position\":3,\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dish").value(dishUid))
                .andExpect(jsonPath("$.data.position").value(3))
                .andExpect(jsonPath("$.data.active").value(false));

        mockMvc.perform(post("/api/menus/" + menuUid + "/add-dish")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("{\"dish_uid\":\"" + dishUid + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message_code").value("MENU_DISH_ALREADY_EXISTS"));
    }

    @Test
    void addDishToMenu_strangerChefIsDenied_butAdminIsAllowed() throws Exception {
        Account owner = register("chef-adddish-owner", UserRole.CHEF);
        Account stranger = register("chef-adddish-stranger", UserRole.CHEF);
        Account admin = register("admin-adddish", UserRole.ADMIN);
        String menuUid = createMenu(owner.token(), "Menu người lạ " + System.nanoTime(), "ACTIVE");
        String dishUid = createDish(owner.token(), "Nem nướng " + System.nanoTime());

        mockMvc.perform(post("/api/menus/" + menuUid + "/add-dish")
                        .header("Authorization", "Bearer " + stranger.token())
                        .contentType("application/json")
                        .content("{\"dish_uid\":\"" + dishUid + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message_code").value("PERMISSION_DENIED"));

        // PORT-NOTE (real quirk): this specific endpoint allows an ADMIN who
        // doesn't own the menu to succeed — see MenuService's class javadoc.
        mockMvc.perform(post("/api/menus/" + menuUid + "/add-dish")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType("application/json")
                        .content("{\"dish_uid\":\"" + dishUid + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dish").value(dishUid));
    }

    // ===================================================================
    // activate/deactivate menu + dish-in-menu, and the chef-only all-dishes view
    // ===================================================================

    @Test
    void activateAndDeactivateMenu_roundTrip() throws Exception {
        Account chef = register("chef-actmenu", UserRole.CHEF);
        String uid = createMenu(chef.token(), "Bật tắt menu " + System.nanoTime(), "DRAFT");

        mockMvc.perform(patch("/api/menus/" + uid + "/activate").header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(patch("/api/menus/" + uid + "/deactivate").header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
    }

    @Test
    void activateAndDeactivateDishInMenu_roundTrip_andUnlinkedDishIsAServerError() throws Exception {
        Account chef = register("chef-actdish", UserRole.CHEF);
        String menuUid = createMenu(chef.token(), "Menu món ăn " + System.nanoTime(), "ACTIVE");
        String linkedDish = createDish(chef.token(), "Cơm tấm " + System.nanoTime());
        String unlinkedDish = createDish(chef.token(), "Bánh cuốn " + System.nanoTime());

        mockMvc.perform(post("/api/menus/" + menuUid + "/add-dish")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("{\"dish_uid\":\"" + linkedDish + "\",\"active\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/menus/" + menuUid + "/dishes/" + linkedDish + "/activate")
                        .header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true));

        mockMvc.perform(patch("/api/menus/" + menuUid + "/dishes/" + linkedDish + "/deactivate")
                        .header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));

        // PORT-NOTE: Django's MenuDish.objects.get(...) is uncaught for a dish
        // that was never linked -> an unhandled 500. Preserved.
        mockMvc.perform(patch("/api/menus/" + menuUid + "/dishes/" + unlinkedDish + "/activate")
                        .header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message_code").value("CONTACT_ADMIN_FOR_SUPPORT"));
    }

    @Test
    void getAllDishesInMenuForChef_ownerOnly_andActiveFilterWorks() throws Exception {
        Account owner = register("chef-alldishes", UserRole.CHEF);
        Account stranger = register("chef-alldishes-stranger", UserRole.CHEF);
        Account customer = register("customer-alldishes", UserRole.CUSTOMER);
        String menuUid = createMenu(owner.token(), "Toàn bộ món " + System.nanoTime(), "ACTIVE");
        String activeDish = createDish(owner.token(), "Món hiện " + System.nanoTime());
        String inactiveDish = createDish(owner.token(), "Món ẩn " + System.nanoTime());

        mockMvc.perform(post("/api/menus/" + menuUid + "/add-dish")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType("application/json")
                        .content("{\"dish_uid\":\"" + activeDish + "\",\"active\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/menus/" + menuUid + "/add-dish")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType("application/json")
                        .content("{\"dish_uid\":\"" + inactiveDish + "\",\"active\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/menus/" + menuUid + "/all-dishes")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get("/api/menus/" + menuUid + "/all-dishes")
                        .header("Authorization", "Bearer " + owner.token())
                        .param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].uid").value(activeDish));

        // CHEF role required at all -> a CUSTOMER is rejected outright (401).
        mockMvc.perform(get("/api/menus/" + menuUid + "/all-dishes")
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isUnauthorized());

        // Holding CHEF is not enough -> the service's strict ownership check denies a stranger (403).
        mockMvc.perform(get("/api/menus/" + menuUid + "/all-dishes")
                        .header("Authorization", "Bearer " + stranger.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message_code").value("PERMISSION_DENIED"));
    }

    @Test
    void anonymousRequestsAreRejectedByTheFilterChain() throws Exception {
        mockMvc.perform(get("/api/menus/mine"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message_code").value("UNAUTHORIZED"));
    }
}
