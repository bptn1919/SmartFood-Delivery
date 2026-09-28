package com.amomeal.marketplace.ingredient.web;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification of the ingredient module through the full stack
 * (real filter chain, real Postgres via Testcontainers) — mirrors
 * ../../backend/ingredient/api.py's controller surface. Modeled on
 * AttachmentControllerTest/AuthControllerTest's pattern (register via the
 * real auth endpoint, then grant the account its Django role via the
 * repository). Roles are the real thing now — {@code CustomUser.roles}, the
 * port of Django auth Group membership — replacing the {@code isStaff} flip
 * this test used while ADMIN was only stood in for.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class IngredientControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomUserRepository customUserRepository;

    /** Registers an account and gives it exactly {@code role} (register assigns CUSTOMER). */
    private String registerWithRole(String prefix, UserRole role) throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        String email = prefix + "+" + nonce + "@amomeal.test";
        String body = """
                {"username":"%s-%s","email":"%s","password":"correct-horse-battery","phone_number":"0900000003"}
                """.formatted(prefix, nonce, email);
        String json = mockMvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString();

        CustomUser user = customUserRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.getRoles().clear();
        user.addRole(role);
        customUserRepository.save(user);
        return extractField(json, "access_token");
    }

    /** Django {@code @require_group(CHEF)} endpoints: /chef, /search, /autocomplete, /suggestions*. */
    private String registerChefAccessToken(String prefix) throws Exception {
        return registerWithRole(prefix, UserRole.CHEF);
    }

    /** Django {@code @require_group(ADMIN)} endpoints: the whole CRUD/alias/import/moderation surface. */
    private String registerAdminAccessToken() throws Exception {
        return registerWithRole("admin", UserRole.ADMIN);
    }

    private String createIngredient(String adminToken, String name) throws Exception {
        String body = """
                {"name":"%s","category":"VEGETABLE","weight":100,"energy":41,"protein":0.9}
                """.formatted(name);
        String json = mockMvc.perform(post("/api/ingredients/")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extractField(json, "uid");
    }

    @Test
    void adminCanCreateAndFetchIngredient_chefCannotCreate() throws Exception {
        String adminToken = registerAdminAccessToken();
        String chefToken = registerChefAccessToken("chef-create");

        // non-admin create -> 401 (this project's GlobalExceptionHandler maps Spring
        // Security's AccessDeniedException to 401 UNAUTHORIZED, same as AuthenticationException
        // — CLAUDE.md §4, a deliberate existing convention, not something this port changes)
        mockMvc.perform(post("/api/ingredients/")
                        .header("Authorization", "Bearer " + chefToken)
                        .contentType("application/json")
                        .content("""
                                {"name":"Ca Rot Chef","category":"VEGETABLE"}
                                """))
                .andExpect(status().isUnauthorized());

        String uid = createIngredient(adminToken, "Ca Rot " + System.nanoTime());
        assertThat(uid).isNotBlank();

        String getJson = mockMvc.perform(get("/api/ingredients/" + uid)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(getJson).contains("\"category\":\"VEGETABLE\"");

        // list all should include it, and response is paginated (content/total_rows)
        mockMvc.perform(get("/api/ingredients/").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"content\"")))
                .andExpect(content().string(containsString("\"total_rows\"")));

        // unknown uid -> 404 INGREDIENT_NOT_FOUND
        mockMvc.perform(get("/api/ingredients/" + java.util.UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("INGREDIENT_NOT_FOUND")));
    }

    @Test
    void duplicateIngredientNameReturnsBadRequest() throws Exception {
        String adminToken = registerAdminAccessToken();
        String name = "Ca Chua " + System.nanoTime();
        createIngredient(adminToken, name);

        mockMvc.perform(post("/api/ingredients/")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("""
                                {"name":"%s","category":"VEGETABLE"}
                                """.formatted(name)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("INGREDIENT_NAME_ALREADY_EXISTS")));
    }

    @Test
    void aliasCreateAndListAsAdmin() throws Exception {
        String adminToken = registerAdminAccessToken();
        String uid = createIngredient(adminToken, "Toi " + System.nanoTime());

        String aliasBody = """
                {"ingredient_uid":"%s","alias":"Toi Ta"}
                """.formatted(uid);
        mockMvc.perform(post("/api/ingredients/aliases")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(aliasBody))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"alias\":\"Toi Ta\"")));

        mockMvc.perform(get("/api/ingredients/aliases?search=Toi Ta")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Toi Ta")));

        // referenced ingredient cannot be soft-deleted
        mockMvc.perform(put("/api/ingredients/" + uid + "/deleted")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("INGREDIENT_IS_REFERENCED")));
    }

    @Test
    void favouriteAndAllergyLifecycle() throws Exception {
        String adminToken = registerAdminAccessToken();
        String chefToken = registerChefAccessToken("chef-pref");
        String uid = createIngredient(adminToken, "Trung Ga " + System.nanoTime());

        String prefBody = """
                {"ingredient_uid":"%s"}
                """.formatted(uid);

        mockMvc.perform(post("/api/ingredients/me/favourites")
                        .header("Authorization", "Bearer " + chefToken)
                        .contentType("application/json").content(prefBody))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/ingredients/me/favourites").header("Authorization", "Bearer " + chefToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(uid)));
        mockMvc.perform(delete("/api/ingredients/me/favourites/" + uid).header("Authorization", "Bearer " + chefToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"data\":true")));

        mockMvc.perform(post("/api/ingredients/me/allergies")
                        .header("Authorization", "Bearer " + chefToken)
                        .contentType("application/json").content(prefBody))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/ingredients/me/allergies").header("Authorization", "Bearer " + chefToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(uid)));
    }

    @Test
    void suggestionLifecycle_createListApproveAliasReject() throws Exception {
        String adminToken = registerAdminAccessToken();
        String chefToken = registerChefAccessToken("chef-sugg");
        String existingIngredientUid = createIngredient(adminToken, "Hanh La " + System.nanoTime());

        // create suggestion (PORT-NOTE endpoint, no Django equivalent)
        String createBody = """
                {"custom_name":"Hanh La Xanh","category":"VEGETABLE"}
                """;
        String createJson = mockMvc.perform(post("/api/ingredients/suggestions")
                        .header("Authorization", "Bearer " + chefToken)
                        .contentType("application/json").content(createBody))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"PENDING\"")))
                .andReturn().getResponse().getContentAsString();
        String suggestionUid = extractField(createJson, "uid");

        // chef sees it in "mine"
        mockMvc.perform(get("/api/ingredients/suggestions/me").header("Authorization", "Bearer " + chefToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(suggestionUid)));

        // admin sees it in "all"
        mockMvc.perform(get("/api/ingredients/suggestions/all").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(suggestionUid)));

        // non-admin cannot approve (401 — see comment on the create-ingredient assertion above)
        mockMvc.perform(post("/api/ingredients/suggestions/" + suggestionUid + "/approve-alias")
                        .header("Authorization", "Bearer " + chefToken)
                        .contentType("application/json")
                        .content("{\"ingredient_uid\":\"" + existingIngredientUid + "\"}"))
                .andExpect(status().isUnauthorized());

        // admin approves as alias of the existing ingredient
        String approveBody = """
                {"ingredient_uid":"%s","resolution_note":"same thing"}
                """.formatted(existingIngredientUid);
        mockMvc.perform(post("/api/ingredients/suggestions/" + suggestionUid + "/approve-alias")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(approveBody))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"APPROVED\"")))
                .andExpect(content().string(containsString(existingIngredientUid)));

        // unknown suggestion -> 404
        mockMvc.perform(post("/api/ingredients/suggestions/" + java.util.UUID.randomUUID() + "/reject")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"rejection_reason\":\"nope\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("INGREDIENT_SUGGESTION_NOT_FOUND")));

        // a second, fresh suggestion: reject it, then confirm soft-delete of a non-pending one fails
        String secondJson = mockMvc.perform(post("/api/ingredients/suggestions")
                        .header("Authorization", "Bearer " + chefToken)
                        .contentType("application/json")
                        .content("""
                                {"custom_name":"Ot Chuong Do","category":"VEGETABLE"}
                                """))
                .andReturn().getResponse().getContentAsString();
        String secondUid = extractField(secondJson, "uid");

        mockMvc.perform(post("/api/ingredients/suggestions/" + secondUid + "/reject")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"rejection_reason\":\"trung lap\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"REJECTED\"")));

        mockMvc.perform(put("/api/ingredients/suggestions/" + secondUid + "/deleted")
                        .header("Authorization", "Bearer " + chefToken))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("INGREDIENT_IS_NOT_PENDING")));
    }

    @Test
    void exportTemplateReturnsRawXlsxNotEnvelope() throws Exception {
        String adminToken = registerAdminAccessToken();
        var result = mockMvc.perform(get("/api/ingredients/export-template")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentType())
                .contains("spreadsheetml.sheet");
        assertThat(result.getResponse().getContentAsByteArray().length).isGreaterThan(0);
    }

    @Test
    void importExcelHappyPathAndValidationErrors() throws Exception {
        String adminToken = registerAdminAccessToken();
        String duplicateName = "Import Dup " + System.nanoTime();
        createIngredient(adminToken, duplicateName);

        byte[] xlsx = buildImportFixture(duplicateName);
        MockMultipartFile file = new MockMultipartFile("file", "ingredients.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);

        String json = mockMvc.perform(multipart("/api/ingredients/import-excel")
                        .file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json).contains("\"created_count\":1");
        assertThat(json).contains("\"failed_count\":2");
        assertThat(json).contains("name is required");
        assertThat(json).contains("Duplicate ingredient name");
    }

    @Test
    void importExcelWithoutFileReturnsBadRequest() throws Exception {
        String adminToken = registerAdminAccessToken();
        mockMvc.perform(multipart("/api/ingredients/import-excel")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    /**
     * Builds a small .xlsx fixture in-memory with Apache POI's writer API
     * (never hand-crafted binary), matching the header row
     * ../../backend/ingredient/constants.py::IMPORT_COLUMNS expects: one
     * valid new ingredient row, one row missing "name" (validation failure),
     * and one row duplicating an already-existing ingredient name.
     */
    private byte[] buildImportFixture(String duplicateName) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("ingredients");
            String[] columns = {"name", "category", "weight", "energy", "protein", "lipid", "carbohydrate", "fiber",
                    "natri", "kali", "cholesterol", "retinol", "caroten", "vitamin_b_1", "vitamin_b_2",
                    "vitamin_pp", "vitamin_c", "calcium", "phosphorus", "fe", "mg", "zn"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]);
            }

            Row valid = sheet.createRow(1);
            valid.createCell(0).setCellValue("Bap Cai Moi " + System.nanoTime());
            valid.createCell(1).setCellValue("VEGETABLE");
            valid.createCell(2).setCellValue(100);
            valid.createCell(3).setCellValue(25);

            Row missingName = sheet.createRow(2);
            missingName.createCell(1).setCellValue("VEGETABLE");

            Row duplicate = sheet.createRow(3);
            duplicate.createCell(0).setCellValue(duplicateName);
            duplicate.createCell(1).setCellValue("VEGETABLE");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static String extractField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
