package com.amomeal.marketplace.profile.web;

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
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack (MockMvc + real Postgres via {@link TestcontainersConfiguration})
 * coverage of {@code profile}'s CRUD controllers — chef profile, customer
 * profile/addresses/favourite dishes, and chef payment. The CUSTOMER→CHEF
 * upgrade flow itself (the module's other star feature) is covered separately
 * in {@code UpgradeToChefControllerTest}. Modeled on
 * {@code menu.web.MenuControllerTest}'s pattern: register through the real
 * auth endpoint, grant the Django-role via the repository.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomUserRepository customUserRepository;
    @Autowired
    private IngredientRepository ingredientRepository;
    @Autowired
    private AttachmentRepository attachmentRepository;

    private record Account(String token, Long userId) {
    }

    private Account register(String prefix, UserRole role) throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        String email = prefix + "+" + nonce + "@amomeal.test";
        String body = """
                {"username":"%s-%s","email":"%s","password":"correct-horse-battery","phone_number":"0900000008"}
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

    private static String extractField(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) {
            throw new IllegalStateException("field " + field + " not found in: " + json);
        }
        start += needle.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private UUID saveIngredient(String name) {
        Ingredient ingredient = Ingredient.builder().name(name).category(IngredientCategory.VEGETABLE).build();
        return ingredientRepository.save(ingredient).getUid();
    }

    // ===================================================================
    // Chef profile
    // ===================================================================

    @Test
    void createChefProfile_requiresChefRole_thenIdempotentUpdateViaMeOverlaysFieldsOnly() throws Exception {
        Account chef = register("chefprofile-create", UserRole.CHEF);
        Account customer = register("chefprofile-create-cust", UserRole.CUSTOMER);

        mockMvc.perform(post("/api/chef-profiles/")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/chef-profiles/")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("{\"bio\":\"Đầu bếp giỏi\",\"specialty\":\"Món Huế\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bio").value("Đầu bếp giỏi"))
                .andExpect(jsonPath("$.data.specialty").value("Món Huế"));

        // Idempotent re-POST with only one field: the other must survive untouched.
        mockMvc.perform(post("/api/chef-profiles/")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("{\"bio\":\"Đầu bếp giỏi hơn\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bio").value("Đầu bếp giỏi hơn"))
                .andExpect(jsonPath("$.data.specialty").value("Món Huế"));
    }

    @Test
    void getChefProfileDetail_requiresChefRole_andPublicEndpointWorksForAnyAuthenticatedUser() throws Exception {
        Account chef = register("chefprofile-detail", UserRole.CHEF);
        Account customer = register("chefprofile-detail-cust", UserRole.CUSTOMER);

        mockMvc.perform(post("/api/chef-profiles/")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json").content("{\"bio\":\"hi\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/chef-profiles/me").header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/chef-profiles/me").header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bio").value("hi"));

        // Public-by-id lookup: reachable by ANY authenticated user (a CUSTOMER here).
        mockMvc.perform(get("/api/chef-profiles/" + chef.userId()).header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user_id").value(chef.userId()));

        mockMvc.perform(get("/api/chef-profiles/999999999").header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("PROFILE_DOES_NOT_EXIST"));
    }

    @Test
    void updateChefProfile_hasNoRoleGate_aNonChefGets404NotUnauthorized() throws Exception {
        Account customer = register("chefprofile-update-noprofile", UserRole.CUSTOMER);
        mockMvc.perform(patch("/api/chef-profiles/me")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json").content("{\"bio\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("PROFILE_DOES_NOT_EXIST"));
    }

    @Test
    void isChefId_reflectsWhetherAChefProfileRowExists() throws Exception {
        Account chef = register("ischefid", UserRole.CHEF);
        mockMvc.perform(get("/api/chef-profiles/is-chef-id").header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.is_chef").value(false));

        mockMvc.perform(post("/api/chef-profiles/")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/chef-profiles/is-chef-id").header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.is_chef").value(true));
    }

    @Test
    void popularChefs_isReachableAndNeverThrowsOnAnEmptyOrPopulatedSystem() throws Exception {
        Account customer = register("popularchefs", UserRole.CUSTOMER);
        mockMvc.perform(get("/api/chef-profiles/popular").header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousIsRejectedByTheFilterChain() throws Exception {
        mockMvc.perform(get("/api/chef-profiles/1")).andExpect(status().isUnauthorized());
    }

    // ===================================================================
    // Customer profile / addresses / favourite dishes
    // ===================================================================

    @Test
    void getCustomerProfile_lazilyCreatesTheProfileRow_andIsOnboardedDefaultsFalse() throws Exception {
        Account customer = register("custprofile-lazy", UserRole.CUSTOMER);
        mockMvc.perform(get("/api/customer-profiles").header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.is_onboarded").value(false))
                .andExpect(jsonPath("$.data.diet_mode").value("NONE"))
                .andExpect(jsonPath("$.data.allergy_mode").value("WARN"));
    }

    @Test
    void updateCustomerProfile_dietModeWithoutLevel_isRejected_thenTogetherSucceeds() throws Exception {
        Account customer = register("custprofile-diet", UserRole.CUSTOMER);

        mockMvc.perform(patch("/api/customer-profiles")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("{\"diet_mode\":\"LOW_CARB\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("HTTP_ERROR"));

        mockMvc.perform(patch("/api/customer-profiles")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("{\"diet_mode\":\"LOW_CARB\",\"diet_level\":\"SOFT\",\"bio\":\"eating healthy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.diet_mode").value("LOW_CARB"))
                .andExpect(jsonPath("$.data.diet_level").value("SOFT"))
                .andExpect(jsonPath("$.data.bio").value("eating healthy"));
    }

    @Test
    void onboardCustomerProfile_withInvalidIngredientUid_isRejected_thenValidUidsSucceed() throws Exception {
        Account customer = register("onboard", UserRole.CUSTOMER);
        UUID allergic = saveIngredient("Đậu phộng " + System.nanoTime());
        UUID favorite = saveIngredient("Rau muống " + System.nanoTime());

        mockMvc.perform(post("/api/customer-profiles/onboard")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"height_cm":170,"weight_kg":65,
                                 "allergic_ingredient_uids":["%s"],"favorite_ingredient_uids":[]}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("HTTP_ERROR"));

        mockMvc.perform(post("/api/customer-profiles/onboard")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"height_cm":170,"weight_kg":65,
                                 "allergic_ingredient_uids":["%s"],"favorite_ingredient_uids":["%s"]}
                                """.formatted(allergic, favorite)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.is_onboarded").value(true));
    }

    @Test
    void addressLifecycle_create_list_getOne_setDefault_softDelete() throws Exception {
        Account customer = register("addresses", UserRole.CUSTOMER);

        // No addresses yet -> both list endpoints 404.
        mockMvc.perform(get("/api/customer-profiles/addresses").header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/customer-profiles/addresses/get-one").header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isNotFound());

        String createBody = """
                {"address":"12 Le Loi","street":"Le Loi","ward":"Ben Nghe","district":"Q1","city":"HCMC"}
                """;
        String json = mockMvc.perform(post("/api/customer-profiles/addresses")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json").content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.full_address").value("12 Le Loi, Le Loi, Ben Nghe, Q1, HCMC"))
                .andReturn().getResponse().getContentAsString();
        long addressId = ((Number) com.jayway.jsonpath.JsonPath.read(json, "$.data.id")).longValue();

        mockMvc.perform(get("/api/customer-profiles/addresses").header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                // List shape excludes the raw address/street/ward/district/city fields.
                .andExpect(jsonPath("$.data[0].address").doesNotExist())
                .andExpect(jsonPath("$.data[0].full_address").value("12 Le Loi, Le Loi, Ben Nghe, Q1, HCMC"));

        mockMvc.perform(put("/api/customer-profiles/addresses/" + addressId + "/set-default")
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Set default address successfully"));

        mockMvc.perform(get("/api/customer-profiles/addresses/get-one").header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selected").value(true));

        mockMvc.perform(put("/api/customer-profiles/addresses/999999/set-default")
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/customer-profiles/addresses/" + addressId + "/delete")
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        // PORT-NOTE (real Django quirk, preserved): soft delete never actually
        // hides the address from this same listing endpoint.
        mockMvc.perform(get("/api/customer-profiles/addresses").header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    private UUID completedDishAttachment() {
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
                """.formatted(name, completedDishAttachment());
        String json = mockMvc.perform(post("/api/dishes/")
                        .header("Authorization", "Bearer " + chefToken)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return (String) com.jayway.jsonpath.JsonPath.read(json, "$.data.uid");
    }

    @Test
    void favoriteDishLifecycle_add_list_remove_removeAgainIs404() throws Exception {
        Account chef = register("favdish-chef", UserRole.CHEF);
        Account customer = register("favdish-cust", UserRole.CUSTOMER);
        String dishUid = createDish(chef.token(), "Bánh xèo " + System.nanoTime());

        mockMvc.perform(post("/api/customer-profiles/favorite-dish/" + dishUid)
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uid").value(dishUid))
                .andExpect(jsonPath("$.data.is_favorite").value(true));

        mockMvc.perform(get("/api/customer-profiles/favorite-dishes")
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].uid").value(dishUid));

        mockMvc.perform(patch("/api/customer-profiles/favorite-dish/" + dishUid)
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/api/customer-profiles/favorite-dishes")
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // Removing an already-removed (never favorited again) dish -> 404.
        mockMvc.perform(patch("/api/customer-profiles/favorite-dish/" + dishUid)
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("FAVORITE_DISH_NOT_FOUND"));
    }

    // ===================================================================
    // Chef payment
    // ===================================================================

    @Test
    void chefPayment_requiresChefRole_createGetDeleteLifecycle_andValidationErrors() throws Exception {
        Account chef = register("chefpayment", UserRole.CHEF);
        Account customer = register("chefpayment-cust", UserRole.CUSTOMER);

        mockMvc.perform(get("/api/chef-payment").header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/chef-payment").header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("PROFILE_DOES_NOT_EXIST"));

        // Invalid bank code -> the Pydantic-field-validator-shaped 401 VALIDATION_ERROR.
        mockMvc.perform(post("/api/chef-payment")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("""
                                {"bank_name":"VCB","bank_code":"12","bank_account_number":"12345678",
                                 "bank_account_name":"NGUYEN VAN A"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message_code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/chef-payment")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json")
                        .content("""
                                {"bank_name":"VCB","bank_code":"123456","bank_account_number":"12345678",
                                 "bank_account_name":"NGUYEN VAN A"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bank_account_number").value("****5678"))
                .andExpect(jsonPath("$.data.is_verified").value(false));

        mockMvc.perform(get("/api/chef-payment").header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bank_account_number").value("****5678"));

        mockMvc.perform(patch("/api/chef-payment").header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));
    }
}
