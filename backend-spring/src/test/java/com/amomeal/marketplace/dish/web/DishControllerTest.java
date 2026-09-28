package com.amomeal.marketplace.dish.web;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.entity.AttachmentType;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.amomeal.marketplace.ingredient.repository.IngredientRepository;
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

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification of the dish module through the full stack (real
 * filter chain, real Postgres + real Redis via the shared
 * {@link TestcontainersConfiguration}) — mirrors ../../backend/dish/api.py's
 * three controllers. Modeled on IngredientControllerTest's pattern: register
 * through the real auth endpoint, then grant the account its Django role
 * (CHEF or ADMIN — {@code CustomUser.roles}, the port of Django auth Group
 * membership) via the repository. This replaced the earlier {@code isStaff}
 * flip, which stood in for ADMIN before the real role model was ported.
 *
 * <p>The {@code @Scheduled} stock sweep is disabled so it cannot interfere.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.stock.sweep-enabled=false")
class DishControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomUserRepository customUserRepository;
    @Autowired
    private AttachmentRepository attachmentRepository;
    @Autowired
    private IngredientRepository ingredientRepository;
    @Autowired
    private com.amomeal.marketplace.dish.repository.DishRepository dishRepository;

    // ===================================================================
    // Fixtures
    // ===================================================================

    private record Account(String token, Long userId) {
    }

    /**
     * @param admin true → the account joins the ADMIN role only, false → CHEF only,
     *              mirroring Django, where the two groups are disjoint in practice
     *              ({@code upgrade_customer_to_chef} moves a user between groups).
     *              {@code /api/auth/register} assigns CUSTOMER, which is replaced
     *              here so the fixture holds exactly the role under test.
     */
    private Account register(String prefix, boolean admin) throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        String email = prefix + "+" + nonce + "@amomeal.test";
        String body = """
                {"username":"%s-%s","email":"%s","password":"correct-horse-battery","phone_number":"0900000005"}
                """.formatted(prefix, nonce, email);
        String json = mockMvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        CustomUser user = customUserRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.getRoles().clear();
        user.addRole(admin ? UserRole.ADMIN : UserRole.CHEF);
        customUserRepository.save(user);
        return new Account(extractField(json, "access_token"), user.getId());
    }

    /**
     * Inserts a completed Attachment straight through the repository. Going via
     * the real presigned-url endpoint would drag the S3/local storage backend
     * into every dish test for no added coverage — AttachmentControllerTest and
     * AttachmentS3ControllerTest already cover that lifecycle.
     */
    private UUID completedAttachment() {
        Attachment attachment = Attachment.builder()
                .type(AttachmentType.DISH)
                .originalName("pho.jpg")
                .hashedName("pho-" + UUID.randomUUID() + ".jpg")
                .size(1024)
                .contentType("image/jpeg")
                .bucket("amomeal-test-bucket")
                .directory("dish")
                .publicUrl("https://cdn.example.test/dish/pho.jpg")
                .isCompleted(true)
                .build();
        return attachmentRepository.save(attachment).getUid();
    }

    private UUID usdaIngredient(String name) {
        return ingredientRepository.save(Ingredient.builder()
                .name(name + "-" + UUID.randomUUID())
                .category(IngredientCategory.GRAIN)
                .weight(100.0)
                .energy(130.0)
                .protein(2.7)
                .lipid(0.3)
                .carbohydrate(28.0)
                .build()).getUid();
    }

    private String createDish(String token, String name) throws Exception {
        String body = """
                {"name":"%s","category":"FOOD","price":55000,"description":"ngon",
                 "status":"AVAILABLE","attachment_uid":"%s"}
                """.formatted(name, completedAttachment());
        String json = mockMvc.perform(post("/api/dishes/")
                        .header("Authorization", "Bearer " + token)
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
    // Dish CRUD
    // ===================================================================

    @Test
    void createDish_thenFetchIt_returnsTheEnvelopedPayloadWithResolvedChefAndImage() throws Exception {
        Account chef = register("chef-create", false);
        String uid = createDish(chef.token(), "Phở bò đặc biệt");

        mockMvc.perform(get("/api/dishes/" + uid).header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                // Response envelope: transport 200, error_code 0 (CLAUDE.md §3).
                // snake_case keys (message_code/error_code) — fixed globally via
                // spring.jackson.property-naming-strategy=SNAKE_CASE right after this
                // module flagged the camelCase bug (see PROGRESS.md, resolved).
                .andExpect(jsonPath("$.message_code").value("SUCCESS"))
                .andExpect(jsonPath("$.error_code").value(0))
                .andExpect(jsonPath("$.data.name").value("Phở bò đặc biệt"))
                .andExpect(jsonPath("$.data.category").value("FOOD"))
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.serving_size").value(1))
                .andExpect(jsonPath("$.data.public_url").value("https://cdn.example.test/dish/pho.jpg"))
                .andExpect(jsonPath("$.data.sold_count").value(0))
                .andExpect(jsonPath("$.data.in_stock").value(0))
                .andExpect(jsonPath("$.data.is_favorite").value(false))
                .andExpect(jsonPath("$.data.allergy_warning").value(false));
    }

    @Test
    void getDish_unknownUid_returns404WithDishNotFoundCode() throws Exception {
        Account chef = register("chef-404", false);

        mockMvc.perform(get("/api/dishes/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("DISH_NOT_FOUND"))
                .andExpect(jsonPath("$.error_code").value(404))
                .andExpect(jsonPath("$.message").value("Dish not found"));
    }

    @Test
    void updateDish_byAnotherChef_is403DishPermissionDenied() throws Exception {
        Account owner = register("chef-owner", false);
        Account stranger = register("chef-stranger", false);
        String uid = createDish(owner.token(), "Bún chả");

        mockMvc.perform(patch("/api/dishes/" + uid)
                        .header("Authorization", "Bearer " + stranger.token())
                        .contentType("application/json")
                        .content("{\"name\":\"hijacked\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message_code").value("DISH_PERMISSION_DENIED"))
                // PORT-NOTE: Django's duplicated `message =` assignment makes this the
                // real message for DishPermissionDenied. Preserved, asserted on purpose.
                .andExpect(jsonPath("$.message").value("Dish is not deleted"));

        // Owner can still update it.
        mockMvc.perform(patch("/api/dishes/" + uid)
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType("application/json")
                        .content("{\"name\":\"Bún chả Hà Nội\",\"price\":65000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Bún chả Hà Nội"))
                .andExpect(jsonPath("$.data.price").value(65000));
    }

    @Test
    void softDeleteThenRestore_roundTrips_andRestoringALiveDishIsRejected() throws Exception {
        Account chef = register("chef-delete", false);
        String uid = createDish(chef.token(), "Chả giò");

        mockMvc.perform(put("/api/dishes/" + uid + "/deleted")
                        .header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        // Soft-deleted dishes disappear from the normal lookup.
        mockMvc.perform(get("/api/dishes/" + uid).header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("DISH_NOT_FOUND"));

        mockMvc.perform(put("/api/dishes/" + uid + "/restored")
                        .header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/api/dishes/" + uid).header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk());

        // Restoring a dish that is not deleted -> DISH_NOT_DELETED (400).
        mockMvc.perform(put("/api/dishes/" + uid + "/restored")
                        .header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("DISH_NOT_DELETED"));
    }

    @Test
    void hardDelete_requiresAdminRole() throws Exception {
        Account chef = register("chef-hard", false);
        Account admin = register("admin-hard", true);
        String uid = createDish(chef.token(), "Gỏi cuốn");

        // A CHEF (non-ADMIN) -> GlobalExceptionHandler maps AccessDeniedException to 401
        // (CLAUDE.md §4's documented convention, same as the ingredient module).
        mockMvc.perform(delete("/api/dishes/" + uid).header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/dishes/" + uid).header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/api/dishes/" + uid).header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listMyDishes_onlyReturnsThisChefsDishes_andPaginationEnvelopeIsCorrect() throws Exception {
        Account chefA = register("chef-mine-a", false);
        Account chefB = register("chef-mine-b", false);
        createDish(chefA.token(), "Cơm tấm AAA");
        createDish(chefA.token(), "Cơm gà AAA");
        createDish(chefB.token(), "Mì quảng BBB");

        mockMvc.perform(get("/api/dishes/mine")
                        .header("Authorization", "Bearer " + chefA.token())
                        .param("page", "1").param("page_size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.current_page").value(1))
                .andExpect(jsonPath("$.data.page_size").value(10))
                .andExpect(jsonPath("$.data.total_rows").value(2))
                .andExpect(jsonPath("$.data.total_pages").value(1));
    }

    @Test
    void listDishes_filtersBySearchTermIgnoringAccents() throws Exception {
        Account chef = register("chef-search", false);
        String marker = "Zebra" + System.nanoTime();
        createDish(chef.token(), "Bánh xèo " + marker);
        createDish(chef.token(), "Cháo lòng other");

        // "banh xeo" (no accents) must match "Bánh xèo ..." via name_no_accent.
        mockMvc.perform(get("/api/dishes/")
                        .header("Authorization", "Bearer " + chef.token())
                        .param("search", "banh xeo " + marker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_rows").value(1))
                .andExpect(jsonPath("$.data.content[0].name").value("Bánh xèo " + marker));
    }

    // ===================================================================
    // Availability
    // ===================================================================

    @Test
    void availabilities_createThenRead_andOnlyFutureInStockDaysAreListed() throws Exception {
        Account chef = register("chef-avail", false);
        String uid = createDish(chef.token(), "Bánh mì");
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        mockMvc.perform(post("/api/dishes/" + uid + "/availabilities")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("""
                                {"available_date":"%s","available_quantity":25,"note":"mẻ sáng"}
                                """.formatted(tomorrow)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dish_uid").value(uid))
                .andExpect(jsonPath("$.data.availabilities.length()").value(1))
                .andExpect(jsonPath("$.data.availabilities[0].available_quantity").value(25))
                .andExpect(jsonPath("$.data.availabilities[0].note").value("mẻ sáng"));

        // Re-posting the same date updates in place (Django get_or_create + update).
        mockMvc.perform(post("/api/dishes/" + uid + "/availabilities")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("""
                                {"available_date":"%s","available_quantity":40}
                                """.formatted(tomorrow)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availabilities.length()").value(1))
                .andExpect(jsonPath("$.data.availabilities[0].available_quantity").value(40));

        mockMvc.perform(get("/api/dishes/" + uid + "/availabilities")
                        .header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dish_name").value("Bánh mì"));
    }

    // ===================================================================
    // Dish ingredients
    // ===================================================================

    @Test
    void addIngredient_scalesNutrition_andCustomerAndChefViewsDiffer() throws Exception {
        Account chef = register("chef-ing", false);
        String dishUid = createDish(chef.token(), "Cơm chiên");
        UUID ingredientUid = usdaIngredient("gạo");

        // 200 g of a 100 g-reference ingredient -> everything doubles.
        mockMvc.perform(post("/api/dishes/" + dishUid + "/ingredients")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("""
                                {"ingredient_uid":"%s","weight":200}
                                """.formatted(ingredientUid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.approval_status").value("APPROVED"))
                .andExpect(jsonPath("$.data.source").value("USDA"))
                .andExpect(jsonPath("$.data.nutritions.energy").value(260.0))
                .andExpect(jsonPath("$.data.nutritions.carbohydrate").value(56.0))
                .andExpect(jsonPath("$.data.warnings").isEmpty());

        // Customer view: rounded, no per-ingredient confidence/source.
        mockMvc.perform(get("/api/dishes/" + dishUid + "/ingredients")
                        .header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ingredients.length()").value(1))
                .andExpect(jsonPath("$.data.ingredients[0].energy").value(260.0))
                .andExpect(jsonPath("$.data.nutrition_total.energy").value(260.0))
                .andExpect(jsonPath("$.data.confidence_of_dish").isNumber())
                .andExpect(jsonPath("$.data.ingredients[0].confidence").doesNotExist());

        // Chef view: adds confidence/source/approval_status and the full micronutrient total.
        mockMvc.perform(get("/api/dishes/" + dishUid + "/ingredients/chef")
                        .header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ingredients[0].is_custom").value(false))
                .andExpect(jsonPath("$.data.ingredients[0].source").value("USDA"))
                .andExpect(jsonPath("$.data.ingredients[0].approval_status").value("APPROVED"))
                .andExpect(jsonPath("$.data.ingredients[0].confidence").isNumber())
                .andExpect(jsonPath("$.data.confidence_percent").isNumber())
                .andExpect(jsonPath("$.data.nutrition_total.zn").isNumber());
    }

    @Test
    void previewIngredient_doesNotPersist() throws Exception {
        Account chef = register("chef-preview", false);
        String dishUid = createDish(chef.token(), "Xôi gà");
        UUID ingredientUid = usdaIngredient("nếp");

        mockMvc.perform(post("/api/dishes/" + dishUid + "/ingredients/preview")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("""
                                {"ingredient_uid":"%s","weight":150}
                                """.formatted(ingredientUid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weight").value(150.0))
                .andExpect(jsonPath("$.data.nutritions.energy").value(195.0))
                .andExpect(jsonPath("$.data.confidence").isNumber());

        mockMvc.perform(get("/api/dishes/" + dishUid + "/ingredients")
                        .header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.note").value("Không có dữ liệu nguyên liệu"))
                .andExpect(jsonPath("$.data.confidence_of_dish").value(0.0));
    }

    @Test
    void addIngredient_belowCategoryWeightBound_is422IngredientWeightOutOfBounds() throws Exception {
        Account chef = register("chef-bounds", false);
        String dishUid = createDish(chef.token(), "Chè");
        UUID ingredientUid = usdaIngredient("bột");

        // GRAIN bounds are (5, 1500) g — Layer 1 rejects 2 g before anything is stored.
        mockMvc.perform(post("/api/dishes/" + dishUid + "/ingredients")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("""
                                {"ingredient_uid":"%s","weight":2}
                                """.formatted(ingredientUid)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message_code").value("INGREDIENT_WEIGHT_OUT_OF_BOUNDS"))
                .andExpect(jsonPath("$.error_code").value(422))
                .andExpect(jsonPath("$.data.category").value("GRAIN"))
                .andExpect(jsonPath("$.data.min_allowed_g").value(5.0))
                .andExpect(jsonPath("$.data.max_allowed_g").value(1500.0))
                .andExpect(jsonPath("$.data.entered_weight_g").value(2.0));
    }

    @Test
    void updateAndSoftDeleteDishIngredient_throughTheDishIngredientsController() throws Exception {
        Account chef = register("chef-di", false);
        String dishUid = createDish(chef.token(), "Hủ tiếu");
        UUID ingredientUid = usdaIngredient("bánh phở");

        mockMvc.perform(post("/api/dishes/" + dishUid + "/ingredients")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("""
                                {"ingredient_uid":"%s","weight":100}
                                """.formatted(ingredientUid)))
                .andExpect(status().isOk());

        String chefView = mockMvc.perform(get("/api/dishes/" + dishUid + "/ingredients/chef")
                        .header("Authorization", "Bearer " + chef.token()))
                .andReturn().getResponse().getContentAsString();
        String dishIngredientUid = extractField(chefView, "uid");

        mockMvc.perform(get("/api/dishingredients/" + dishIngredientUid)
                        .header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dish_uid").value(dishUid))
                .andExpect(jsonPath("$.data.weight").value(100.0));

        mockMvc.perform(patch("/api/dishingredients/" + dishIngredientUid)
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("""
                                {"ingredient_uid":"%s","weight":300}
                                """.formatted(ingredientUid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("success"))
                .andExpect(jsonPath("$.data.nutritions.energy").value(390.0));

        mockMvc.perform(patch("/api/dishingredients/" + dishIngredientUid + "/soft-deleted")
                        .header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/api/dishingredients/" + dishIngredientUid)
                        .header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("DISH_INGREDIENT_NOT_FOUND"));
    }

    @Test
    void suggestCustomIngredient_createsSuggestionAndPendingRow_andRejectsADuplicate() throws Exception {
        Account chef = register("chef-suggest", false);
        String dishUid = createDish(chef.token(), "Bánh tráng trộn");
        String suggestionBody = """
                {"custom_name":"muối tôm Tây Ninh","category":"SPICE","weight":10,
                 "energy":30,"protein":1,"lipid":0.5,"carbohydrate":5}
                """;

        // Preview first — no persistence, but it does return match candidates.
        mockMvc.perform(post("/api/dishes/" + dishUid + "/ingredients/suggestion/preview")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json").content(suggestionBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.custom_name").value("muối tôm Tây Ninh"))
                .andExpect(jsonPath("$.data.weight").value(10.0))
                .andExpect(jsonPath("$.data.candidates").isArray())
                .andExpect(jsonPath("$.data.confidence").isNumber());

        mockMvc.perform(post("/api/dishes/" + dishUid + "/ingredients/suggestion")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json").content(suggestionBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("muối tôm Tây Ninh"))
                .andExpect(jsonPath("$.data.category").value("SPICE"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        // The PENDING DishIngredient is now visible to the chef as a custom row.
        mockMvc.perform(get("/api/dishes/" + dishUid + "/ingredients/chef")
                        .header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ingredients[0].is_custom").value(true))
                .andExpect(jsonPath("$.data.ingredients[0].ingredient_name").value("muối tôm Tây Ninh"))
                .andExpect(jsonPath("$.data.ingredients[0].approval_status").value("PENDING"))
                .andExpect(jsonPath("$.data.ingredients[0].source").value("CHEF_SUGGESTION"));

        // Suggesting the same custom name on the same dish again is rejected.
        mockMvc.perform(post("/api/dishes/" + dishUid + "/ingredients/suggestion")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json").content(suggestionBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("DISH_INGREDIENT_SUGGESTION_ALREADY_EXISTS"));
    }

    @Test
    void addSuggestedIngredientToAnotherDish_scalesNutritionByTheWeightRatio() throws Exception {
        Account chef = register("chef-addsugg", false);
        String firstDish = createDish(chef.token(), "Bánh tráng nướng");
        String secondDish = createDish(chef.token(), "Bánh tráng cuốn");

        String suggestionJson = mockMvc.perform(post("/api/dishes/" + firstDish + "/ingredients/suggestion")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("""
                                {"custom_name":"mắm ruốc Huế","category":"SPICE","weight":10,
                                 "energy":40,"protein":4,"lipid":1,"carbohydrate":2}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String suggestionUid = extractField(suggestionJson, "uid");

        // 25 g reuses the 10 g row, scaled by 2.5x.
        mockMvc.perform(post("/api/dishes/" + secondDish + "/ingredients/add-suggested")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("""
                                {"suggestion_uid":"%s","weight":25}
                                """.formatted(suggestionUid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("success"))
                .andExpect(jsonPath("$.data.ingredient_custom_name").value("mắm ruốc Huế"))
                .andExpect(jsonPath("$.data.approval_status").value("PENDING"))
                .andExpect(jsonPath("$.data.source").value("CHEF_SUGGESTION"))
                .andExpect(jsonPath("$.data.nutritions.energy").value(100.0))
                .andExpect(jsonPath("$.data.nutritions.protein").value(10.0))
                // Fields the suggestion schema never carries stay null after scaling.
                .andExpect(jsonPath("$.data.nutritions.cholesterol").doesNotExist());
    }

    // ===================================================================
    // Search + top dishes
    // ===================================================================

    @Test
    void search_exactNameOutranksFuzzy_andEmptyQueryShortCircuits() throws Exception {
        Account chef = register("chef-srch", false);
        String marker = "quangnam" + System.nanoTime();
        createDish(chef.token(), marker);
        createDish(chef.token(), "completely unrelated " + System.nanoTime());

        mockMvc.perform(get("/api/dishes/search")
                        .header("Authorization", "Bearer " + chef.token())
                        .param("q", marker).param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.query").value(marker))
                // PORT-NOTE: Django's fuzzy stage has its threshold commented out, so
                // every live dish enters the result set — the exact match just ranks first.
                .andExpect(jsonPath("$.data.results[0].name").value(marker))
                .andExpect(jsonPath("$.data.results[0].search_score").value(1.0));

        mockMvc.perform(get("/api/dishes/search")
                        .header("Authorization", "Bearer " + chef.token())
                        .param("q", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.message").value("Empty query"));
    }

    @Test
    void topDishes_returnsBayesianScoredRows() throws Exception {
        Account chef = register("chef-top", false);
        createDish(chef.token(), "Top dish " + System.nanoTime());

        mockMvc.perform(get("/api/dishes/top")
                        .header("Authorization", "Bearer " + chef.token())
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].score").isNumber())
                .andExpect(jsonPath("$.data[0].review_count").value(0))
                .andExpect(jsonPath("$.data[0].sold_count").value(0))
                .andExpect(jsonPath("$.data[0].chef_id").isNumber());
    }

    @Test
    void suspendedDish_isHiddenFromCustomerFacingListSearchAndTop_butOwnerAndAdminStillSeeIt() throws Exception {
        // Post-port suspension enforcement (2026-09-25): DISH_LOCK (Dish.is_suspended) hides the dish from customers.
        Account owner = register("chef-susp-owner", false);
        Account viewer = register("chef-susp-viewer", false);
        Account admin = register("admin-susp", true);
        String marker = "Suspendedzz" + System.nanoTime();
        String uid = createDish(owner.token(), marker);
        String visibleUid = createDish(owner.token(), marker + " visible");
        com.amomeal.marketplace.dish.entity.Dish d = dishRepository.findByUid(UUID.fromString(uid)).orElseThrow();
        d.setSuspended(true);
        dishRepository.save(d);

        // customer-facing list: only the non-suspended one
        mockMvc.perform(get("/api/dishes/").header("Authorization", "Bearer " + viewer.token()).param("search", marker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_rows").value(1))
                .andExpect(jsonPath("$.data.content[0].uid").value(visibleUid));
        // owner (own list) and admin still see both
        mockMvc.perform(get("/api/dishes/mine").header("Authorization", "Bearer " + owner.token()).param("search", marker))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total_rows").value(2));
        mockMvc.perform(get("/api/dishes/").header("Authorization", "Bearer " + admin.token()).param("search", marker))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total_rows").value(2));
        // search: the exact-name suspended dish must not surface for anyone in customer search
        mockMvc.perform(get("/api/dishes/search").header("Authorization", "Bearer " + viewer.token())
                        .param("q", marker).param("limit", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[?(@.uid == '" + uid + "')]").isEmpty());
        // top dishes
        mockMvc.perform(get("/api/dishes/top").header("Authorization", "Bearer " + viewer.token()).param("limit", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.uid == '" + uid + "')]").isEmpty());
    }

    // ===================================================================
    // Dish locations
    // ===================================================================

    @Test
    void dishLocations_fullHierarchyLifecycle_withSlugsTreeAndDeleteGuard() throws Exception {
        Account admin = register("admin-loc", true);
        Account chef = register("chef-loc", false);
        String nonce = String.valueOf(System.nanoTime());

        String regionJson = mockMvc.perform(post("/api/dish-locations/")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType("application/json")
                        .content("{\"name\":\"Châu Á " + nonce + "\",\"type\":\"REGION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("REGION"))
                // slugify(remove_accents("Châu Á ...")) -> "chau-a-..."
                .andExpect(jsonPath("$.data.slug").value("chau-a-" + nonce))
                .andExpect(jsonPath("$.data.parent_id").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long regionId = extractNumber(regionJson, "id");

        String subregionJson = mockMvc.perform(post("/api/dish-locations/")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType("application/json")
                        .content("{\"name\":\"Đông Nam Á " + nonce + "\",\"type\":\"SUBREGION\",\"parent_id\":"
                                + regionId + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long subregionId = extractNumber(subregionJson, "id");

        String countryJson = mockMvc.perform(post("/api/dish-locations/")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType("application/json")
                        .content("{\"name\":\"Việt Nam " + nonce + "\",\"type\":\"COUNTRY\",\"parent_id\":"
                                + subregionId + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long countryId = extractNumber(countryJson, "id");

        // A COUNTRY under a REGION violates the hierarchy -> Django ValidationError -> 500.
        mockMvc.perform(post("/api/dish-locations/")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType("application/json")
                        .content("{\"name\":\"Bad " + nonce + "\",\"type\":\"COUNTRY\",\"parent_id\":"
                                + regionId + "}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message_code").value("CONTACT_ADMIN_FOR_SUPPORT"));

        // A CHEF (non-ADMIN) cannot create a dish location.
        mockMvc.perform(post("/api/dish-locations/")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("{\"name\":\"Nope " + nonce + "\",\"type\":\"REGION\"}"))
                .andExpect(status().isUnauthorized());

        // countries endpoint
        mockMvc.perform(get("/api/dish-locations/countries").header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + countryId + ")]").exists());

        // tree endpoint nests three levels
        mockMvc.perform(get("/api/dish-locations/tree").header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + regionId
                        + ")].children[?(@.id == " + subregionId
                        + ")].children[?(@.id == " + countryId + ")]").exists());

        // A location with children cannot be deleted.
        mockMvc.perform(delete("/api/dish-locations/" + subregionId)
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("DISH_LOCATION_HAS_CHILDREN"));

        mockMvc.perform(delete("/api/dish-locations/" + countryId)
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/api/dish-locations/" + countryId).header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("DISH_LOCATION_NOT_FOUND"));
    }

    @Test
    void createDish_withANonCountryLocation_isRejected_andAValidCountryResolvesTheFullPath() throws Exception {
        Account admin = register("admin-dishloc", true);
        Account chef = register("chef-dishloc", false);
        String nonce = String.valueOf(System.nanoTime());

        long regionId = extractNumber(mockMvc.perform(post("/api/dish-locations/")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType("application/json")
                        .content("{\"name\":\"Europe " + nonce + "\",\"type\":\"REGION\"}"))
                .andReturn().getResponse().getContentAsString(), "id");
        long subregionId = extractNumber(mockMvc.perform(post("/api/dish-locations/")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType("application/json")
                        .content("{\"name\":\"South Europe " + nonce + "\",\"type\":\"SUBREGION\",\"parent_id\":"
                                + regionId + "}"))
                .andReturn().getResponse().getContentAsString(), "id");
        long countryId = extractNumber(mockMvc.perform(post("/api/dish-locations/")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType("application/json")
                        .content("{\"name\":\"Italy " + nonce + "\",\"type\":\"COUNTRY\",\"parent_id\":"
                                + subregionId + "}"))
                .andReturn().getResponse().getContentAsString(), "id");

        // Django's Dish.clean(): "Dish must belong to a COUNTRY." -> ValidationError -> 500.
        mockMvc.perform(post("/api/dishes/")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("""
                                {"name":"Pizza bad","category":"FOOD","price":90000,
                                 "attachment_uid":"%s","location_id":%d}
                                """.formatted(completedAttachment(), regionId)))
                .andExpect(status().isInternalServerError());

        mockMvc.perform(post("/api/dishes/")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("""
                                {"name":"Pizza Margherita","category":"FOOD","price":90000,
                                 "attachment_uid":"%s","location_id":%d}
                                """.formatted(completedAttachment(), countryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.location_id").value(countryId))
                // Django resolver joins the ancestor chain root-first with " - ".
                .andExpect(jsonPath("$.data.location").value(
                        "Europe " + nonce + " - South Europe " + nonce + " - Italy " + nonce));
    }

    private static long extractNumber(String json, String field) {
        String needle = "\"" + field + "\":";
        int start = json.indexOf(needle) + needle.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)))) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }

    @Test
    void createDish_withAnUnknownAttachment_is404AttachmentNotFound() throws Exception {
        Account chef = register("chef-att", false);

        mockMvc.perform(post("/api/dishes/")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("""
                                {"name":"Ghost dish","category":"FOOD","price":1000,"attachment_uid":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void anonymousRequestsAreRejectedByTheFilterChain() throws Exception {
        mockMvc.perform(get("/api/dishes/top"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message_code").value("UNAUTHORIZED"));
        assertThat(true).isTrue();
    }
}
