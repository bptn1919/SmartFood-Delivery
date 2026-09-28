package com.amomeal.marketplace.users.web;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The effect of replacing the {@code isStaff} ADMIN stand-in (and the
 * "CHEF endpoints are open to anyone authenticated" gap) with Django's real
 * role model, asserted across the two modules that flagged it —
 * {@code ingredient} and {@code dish}.
 *
 * <p>The matrix under test comes straight from the Django decorators:
 * {@code @require_group(ADMIN)} on the ingredient CRUD/alias/import/moderation
 * surface and on dish hard-delete + dish-location writes;
 * {@code @require_group(CHEF)} on ingredient search/autocomplete/suggestions and
 * on dish create / my-dishes.
 *
 * <p>Note the status: this project's {@code GlobalExceptionHandler} maps Spring
 * Security's {@code AccessDeniedException} to <b>401</b>, not 403 — a deliberate
 * pre-existing convention (CLAUDE.md §4), not something these checks introduce.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.stock.sweep-enabled=false")
class RoleAuthorizationControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomUserRepository customUserRepository;
    @Autowired
    private AttachmentRepository attachmentRepository;

    private String tokenFor(String prefix, UserRole role) throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        String email = prefix + "+" + nonce + "@amomeal.test";
        String json = mockMvc.perform(post("/api/auth/register").contentType("application/json").content("""
                        {"username":"%s-%s","email":"%s","password":"correct-horse-battery"}
                        """.formatted(prefix, nonce, email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        CustomUser user = customUserRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.getRoles().clear();
        user.addRole(role);
        customUserRepository.save(user);

        String marker = "\"access_token\":\"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }

    // =====================================================================
    // ingredient — @require_group(ADMIN)
    // =====================================================================

    @Test
    void ingredientAdminEndpoints_acceptOnlyTheAdminRole() throws Exception {
        String admin = tokenFor("role-adm", UserRole.ADMIN);
        String chef = tokenFor("role-chef", UserRole.CHEF);
        String customer = tokenFor("role-cus", UserRole.CUSTOMER);

        String body = """
                {"name":"Rau Muong %d","category":"VEGETABLE","weight":100,"energy":20}
                """.formatted(System.nanoTime());

        mockMvc.perform(post("/api/ingredients/").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());

        for (String token : new String[]{chef, customer}) {
            mockMvc.perform(post("/api/ingredients/").header("Authorization", "Bearer " + token)
                            .contentType("application/json").content(body))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/ingredients/aliases").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/ingredients/suggestions/all").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    // =====================================================================
    // ingredient — @require_group(CHEF)
    // =====================================================================

    @Test
    void ingredientChefEndpoints_acceptOnlyTheChefRole_previouslyOpenToAnyone() throws Exception {
        String chef = tokenFor("role-chef2", UserRole.CHEF);
        String customer = tokenFor("role-cus2", UserRole.CUSTOMER);
        String admin = tokenFor("role-adm2", UserRole.ADMIN);

        mockMvc.perform(get("/api/ingredients/search?search=ca").header("Authorization", "Bearer " + chef))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/ingredients/autocomplete?search=ca").header("Authorization", "Bearer " + chef))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/ingredients/suggestions/me").header("Authorization", "Bearer " + chef))
                .andExpect(status().isOk());

        // Before the real role model landed, BOTH of these were 200 — that gap is
        // exactly what the `ingredient`/`dish` ports flagged. Django gates them with
        // @require_group(CHEF), and ADMIN is a different group.
        for (String token : new String[]{customer, admin}) {
            mockMvc.perform(get("/api/ingredients/search?search=ca").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/ingredients/autocomplete?search=ca").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/ingredients/suggestions/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }

        // Endpoints Django leaves ungated stay reachable for everyone authenticated.
        mockMvc.perform(get("/api/ingredients/me/favourites").header("Authorization", "Bearer " + customer))
                .andExpect(status().isOk());
    }

    // =====================================================================
    // dish — @require_group(CHEF) / @require_group(ADMIN)
    // =====================================================================

    @Test
    void dishCreateAndMyDishes_acceptOnlyTheChefRole() throws Exception {
        String chef = tokenFor("role-dchef", UserRole.CHEF);
        String customer = tokenFor("role-dcus", UserRole.CUSTOMER);

        String body = """
                {"name":"Pho %d","category":"FOOD","price":55000,"description":"ngon",
                 "status":"AVAILABLE","attachment_uid":"%s"}
                """.formatted(System.nanoTime(), completedAttachment());

        mockMvc.perform(post("/api/dishes/").header("Authorization", "Bearer " + chef)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/dishes/mine").header("Authorization", "Bearer " + chef))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/dishes/").header("Authorization", "Bearer " + customer)
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/dishes/mine").header("Authorization", "Bearer " + customer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void dishAdminEndpoints_acceptOnlyTheAdminRole() throws Exception {
        String admin = tokenFor("role-dadm", UserRole.ADMIN);
        String chef = tokenFor("role-dchef2", UserRole.CHEF);

        // hard delete: ADMIN-gated, so a chef never gets as far as the 404.
        mockMvc.perform(delete("/api/dishes/" + UUID.randomUUID()).header("Authorization", "Bearer " + chef))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/dishes/" + UUID.randomUUID()).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());

        // dish-location writes: ADMIN-gated; reads are open to any authenticated user.
        String location = """
                {"name":"Vung %d","type":"REGION"}
                """.formatted(System.nanoTime());
        mockMvc.perform(post("/api/dish-locations/").header("Authorization", "Bearer " + chef)
                        .contentType("application/json").content(location))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/dish-locations/").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content(location))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/dish-locations/").header("Authorization", "Bearer " + chef))
                .andExpect(status().isOk());
    }

    private UUID completedAttachment() {
        return attachmentRepository.save(Attachment.builder()
                .type(AttachmentType.DISH)
                .originalName("pho.jpg")
                .hashedName("pho-" + UUID.randomUUID() + ".jpg")
                .size(1024)
                .contentType("image/jpeg")
                .bucket("amomeal-test-bucket")
                .directory("dish")
                .publicUrl("https://cdn.example.test/dish/pho.jpg")
                .isCompleted(true)
                .build()).getUid();
    }
}
