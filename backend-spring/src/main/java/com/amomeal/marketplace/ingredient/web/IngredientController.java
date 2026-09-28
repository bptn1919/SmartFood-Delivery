package com.amomeal.marketplace.ingredient.web;

import com.amomeal.marketplace.ingredient.dto.*;
import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.amomeal.marketplace.ingredient.service.IngredientImportExportService;
import com.amomeal.marketplace.ingredient.service.IngredientPreferenceService;
import com.amomeal.marketplace.ingredient.service.IngredientService;
import com.amomeal.marketplace.ingredient.service.IngredientSuggestionService;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/ingredient/api.py::IngredientController. Endpoint
 * paths/methods cross-checked against
 * FE-admin/src/utils/constants.js::API_ENDPOINTS.INGREDIENTS and
 * FE-admin/src/services/ingredientService.js.
 *
 * <p>Role checks are the REAL Django ones (the earlier {@code isStaff} stand-in
 * was replaced when the {@code users} module's role model was ported — see
 * PROGRESS.md "Part 1 findings"). Django gates these endpoints with
 * {@code @require_group(UserTypeEnum.ADMIN)} / {@code @require_group(
 * UserTypeEnum.CHEF)}, i.e. membership of the auth Group of that name, which is
 * now {@code CustomUser.roles} → {@code ROLE_ADMIN}/{@code ROLE_CHEF}
 * authorities (wired in {@code JwtAuthenticationFilter}), so
 * {@code hasRole('ADMIN')} / {@code hasRole('CHEF')} below are 1:1 with
 * {@code ingredient/api.py}'s decorators.
 *
 * <p>Note (pre-existing convention, not introduced here): this project's
 * {@code GlobalExceptionHandler} maps Spring Security's
 * {@code AccessDeniedException} to <b>401</b>, not 403 — CLAUDE.md §4.
 */
@RestController
@RequestMapping("/api/ingredients")
@RequiredArgsConstructor
public class IngredientController {

    private final IngredientService ingredientService;
    private final IngredientSuggestionService suggestionService;
    private final IngredientImportExportService importExportService;
    private final IngredientPreferenceService preferenceService;

    // ===================================================================
    // CRUD
    // ===================================================================

    @PostMapping({"", "/"})
    @PreAuthorize("hasRole('ADMIN')")
    public IngredientResponse createNewIngredient(@AuthenticationPrincipal CustomUser user,
                                                   @Valid @RequestBody IngredientRequest payload) {
        return ingredientService.createNewIngredient(user.getId(), payload);
    }

    @GetMapping({"", "/"})
    public PageResponse<IngredientResponse> getAllIngredients(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String categories,
            @RequestParam(name = "order_by", required = false, defaultValue = "updated_at") String orderBy,
            @RequestParam(name = "sort_type", required = false, defaultValue = "desc") String sortType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return ingredientService.getAllIngredients(search, categories, orderBy, sortType, page, pageSize);
    }

    @GetMapping("/chef")
    @PreAuthorize("hasRole('CHEF')")
    public PageResponse<IngredientSearchItem> getAllIngredientsForChef(
            @AuthenticationPrincipal CustomUser user,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String categories,
            @RequestParam(name = "order_by", required = false, defaultValue = "updated_at") String orderBy,
            @RequestParam(name = "sort_type", required = false, defaultValue = "desc") String sortType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return ingredientService.getAllIngredientsForChef(user.getId(), search, categories, orderBy, sortType, page, pageSize);
    }

    /**
     * PORT-NOTE: returns raw {@code byte[]} (not {@code ResponseEntity<byte[]>}) and sets
     * headers directly on the servlet response — {@code ResponseEnvelopeAdvice.supports()}
     * (common module, not touched by this port) excludes a return type of exactly
     * {@code byte[].class}, but that check can't see through {@code ResponseEntity<byte[]>}'s
     * generic erasure (same erasure trap CLAUDE.md §3 documents for {@code ApiResponse<T>}),
     * which would wrap these Excel bytes in the JSON envelope and corrupt the file. Mirrors
     * Django's raw {@code HttpResponse} (not the JSON envelope) for this one endpoint.
     */
    @GetMapping("/export-template")
    @PreAuthorize("hasRole('ADMIN')")
    public byte[] exportIngredientTemplate(HttpServletResponse response) {
        byte[] bytes = importExportService.exportTemplateExcel();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ingredient_import_template.xlsx");
        return bytes;
    }

    @PostMapping("/import-excel")
    @PreAuthorize("hasRole('ADMIN')")
    public IngredientImportResult importIngredientsExcel(@AuthenticationPrincipal CustomUser user,
                                                           @RequestParam(value = "file", required = false) MultipartFile file) {
        return importExportService.importFromExcel(user.getId(), file);
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('CHEF')")
    public List<IngredientSearchItem> searchIngredients(
            @AuthenticationPrincipal CustomUser user,
            @RequestParam(required = false, defaultValue = "") String query,
            @RequestParam(required = false) String categories,
            @RequestParam(defaultValue = "100") int limit) {
        return ingredientService.searchIngredients(user, query, categories, limit);
    }

    @GetMapping("/autocomplete")
    @PreAuthorize("hasRole('CHEF')")
    public List<IngredientAutocompleteItem> autocompleteIngredients(
            @RequestParam(required = false, defaultValue = "") String query,
            @RequestParam(defaultValue = "10") int limit) {
        return ingredientService.autocomplete(query, limit);
    }

    // ===================================================================
    // Aliases
    // ===================================================================

    @PostMapping("/aliases")
    @PreAuthorize("hasRole('ADMIN')")
    public IngredientAliasResponse createIngredientAlias(@AuthenticationPrincipal CustomUser user,
                                                           @Valid @RequestBody IngredientAliasCreateRequest payload) {
        return ingredientService.createAlias(user.getId(), payload);
    }

    @GetMapping("/aliases")
    @PreAuthorize("hasRole('ADMIN')")
    public List<IngredientAliasResponse> listIngredientAliases(@RequestParam(required = false) String search) {
        return ingredientService.listAliases(search);
    }

    // ===================================================================
    // Suggestions (moderation queue)
    // ===================================================================

    /**
     * PORT-NOTE: no Django equivalent — see IngredientSuggestionCreateRequest
     * javadoc. CHEF-gated because the Django flow it substitutes for
     * ({@code POST /api/dishes/{uid}/ingredients/suggestion}) is reachable only
     * by a chef who owns the dish.
     */
    @PostMapping("/suggestions")
    @PreAuthorize("hasRole('CHEF')")
    public IngredientSuggestionResponse createIngredientSuggestion(
            @AuthenticationPrincipal CustomUser user,
            @Valid @RequestBody IngredientSuggestionCreateRequest payload) {
        return suggestionService.createSuggestion(user, payload);
    }

    @GetMapping("/suggestions/all")
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<IngredientSuggestionResponse> listIngredientSuggestions(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) IngredientCategory category,
            @RequestParam(name = "order_by", required = false, defaultValue = "created_at") String orderBy,
            @RequestParam(name = "sort_type", required = false, defaultValue = "desc") String sortType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return suggestionService.listSuggestions(status, search, category, orderBy, sortType, null, page, pageSize);
    }

    @GetMapping("/suggestions/me")
    @PreAuthorize("hasRole('CHEF')")
    public PageResponse<IngredientSuggestionResponse> getMyIngredientSuggestions(
            @AuthenticationPrincipal CustomUser user,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) IngredientCategory category,
            @RequestParam(name = "order_by", required = false, defaultValue = "created_at") String orderBy,
            @RequestParam(name = "sort_type", required = false, defaultValue = "desc") String sortType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return suggestionService.listSuggestions(status, search, category, orderBy, sortType, user.getId(), page, pageSize);
    }

    @PutMapping("/suggestions/{uid}/deleted")
    @PreAuthorize("hasRole('CHEF')")
    public boolean softDeleteIngredientSuggestion(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        return suggestionService.softDeleteSuggestion(user, uid);
    }

    @PostMapping("/suggestions/{uid}/approve-new")
    @PreAuthorize("hasRole('ADMIN')")
    public IngredientSuggestionApproveNewResponse approveNewIngredientSuggestion(
            @AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
            @Valid @RequestBody(required = false) IngredientSuggestionApproveNewRequest payload) {
        return suggestionService.approveNew(user, uid, payload == null ? new IngredientSuggestionApproveNewRequest(null) : payload);
    }

    @PostMapping("/suggestions/{uid}/approve-alias")
    @PreAuthorize("hasRole('ADMIN')")
    public IngredientSuggestionApproveAliasResponse approveAliasIngredientSuggestion(
            @AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
            @Valid @RequestBody IngredientSuggestionApproveAliasRequest payload) {
        return suggestionService.approveAlias(user, uid, payload);
    }

    @PostMapping("/suggestions/{uid}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public IngredientSuggestionRejectResponse rejectIngredientSuggestion(
            @AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
            @Valid @RequestBody IngredientSuggestionRejectRequest payload) {
        return suggestionService.reject(user, uid, payload.rejectionReason());
    }

    // ===================================================================
    // Favourites / allergies
    // ===================================================================

    @PostMapping("/me/favourites")
    public UserIngredientPreferenceResponse addFavouriteIngredient(@AuthenticationPrincipal CustomUser user,
                                                                     @Valid @RequestBody UserIngredientPreferenceRequest payload) {
        return preferenceService.addFavourite(user, payload);
    }

    @GetMapping("/me/favourites")
    public List<UserIngredientPreferenceResponse> listFavouriteIngredients(@AuthenticationPrincipal CustomUser user) {
        return preferenceService.listFavourites(user);
    }

    @DeleteMapping("/me/favourites/{ingredientUid}")
    public boolean removeFavouriteIngredient(@AuthenticationPrincipal CustomUser user, @PathVariable UUID ingredientUid) {
        return preferenceService.removeFavourite(user, ingredientUid);
    }

    @PostMapping("/me/allergies")
    public UserIngredientPreferenceResponse addAllergicIngredient(@AuthenticationPrincipal CustomUser user,
                                                                    @Valid @RequestBody UserIngredientPreferenceRequest payload) {
        return preferenceService.addAllergic(user, payload);
    }

    @GetMapping("/me/allergies")
    public List<UserIngredientPreferenceResponse> listAllergicIngredients(@AuthenticationPrincipal CustomUser user) {
        return preferenceService.listAllergic(user);
    }

    @DeleteMapping("/me/allergies/{ingredientUid}")
    public boolean removeAllergicIngredient(@AuthenticationPrincipal CustomUser user, @PathVariable UUID ingredientUid) {
        return preferenceService.removeAllergic(user, ingredientUid);
    }

    // ===================================================================
    // Single-ingredient (path-variable routes go last so literal paths above win)
    // ===================================================================

    @GetMapping("/{uid}")
    public IngredientResponse getIngredient(@PathVariable UUID uid) {
        return ingredientService.getIngredientResponseByUid(uid);
    }

    @PutMapping("/{uid}")
    @PreAuthorize("hasRole('ADMIN')")
    public IngredientResponse updateIngredient(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
                                                @Valid @RequestBody IngredientRequest payload) {
        return ingredientService.updateIngredient(user.getId(), uid, payload);
    }

    @PutMapping("/{uid}/deleted")
    @PreAuthorize("hasRole('ADMIN')")
    public boolean softDeleteIngredient(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        return ingredientService.softDeleteIngredient(user.getId(), uid);
    }

    @DeleteMapping("/{uid}")
    @PreAuthorize("hasRole('ADMIN')")
    public boolean hardDeleteIngredient(@PathVariable UUID uid) {
        return ingredientService.hardDeleteIngredient(uid);
    }

    @PutMapping("/{uid}/restored")
    @PreAuthorize("hasRole('ADMIN')")
    public boolean restoreIngredient(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        return ingredientService.restoreIngredient(user.getId(), uid);
    }
}
