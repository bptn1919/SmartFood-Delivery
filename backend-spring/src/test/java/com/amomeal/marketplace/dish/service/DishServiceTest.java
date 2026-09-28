package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.attachment.service.AttachmentService;
import com.amomeal.marketplace.dish.dto.DishIngredientCreateRequest;
import com.amomeal.marketplace.dish.dto.DishIngredientSaveResponse;
import com.amomeal.marketplace.dish.dto.DishIngredientUpdateRequest;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishIngredient;
import com.amomeal.marketplace.dish.exception.DishIngredientValidationException;
import com.amomeal.marketplace.dish.exception.DishIsNotDeletedException;
import com.amomeal.marketplace.dish.exception.DishNotFoundException;
import com.amomeal.marketplace.dish.exception.DishPermissionDeniedException;
import com.amomeal.marketplace.dish.repository.DishAvailabilityRepository;
import com.amomeal.marketplace.dish.repository.DishIngredientRepository;
import com.amomeal.marketplace.dish.repository.DishLocationRepository;
import com.amomeal.marketplace.dish.repository.DishRepository;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.amomeal.marketplace.ingredient.entity.IngredientImportStatus;
import com.amomeal.marketplace.ingredient.entity.IngredientSource;
import com.amomeal.marketplace.ingredient.repository.AllergicIngredientRepository;
import com.amomeal.marketplace.ingredient.repository.IngredientRepository;
import com.amomeal.marketplace.ingredient.repository.IngredientSuggestionRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests (Mockito) for the DishService branches that are easier to pin down
 * without a database — in particular the Django quirks this port deliberately
 * preserves. The full-stack coverage lives in
 * {@code com.amomeal.marketplace.dish.web.DishControllerTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DishServiceTest {

    @Mock private DishRepository dishRepository;
    @Mock private DishIngredientRepository dishIngredientRepository;
    @Mock private DishAvailabilityRepository availabilityRepository;
    @Mock private DishLocationRepository locationRepository;
    @Mock private IngredientRepository ingredientRepository;
    @Mock private IngredientSuggestionRepository suggestionRepository;
    @Mock private AllergicIngredientRepository allergicIngredientRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private DishInventoryService inventoryService;
    @Mock private DishSuggestionCandidateFinder candidateFinder;
    @Mock private DishStatsProvider statsProvider;
    @Mock private DishUserContext userContext;

    private DishService service;

    private CustomUser owner;
    private CustomUser stranger;
    private CustomUser admin;
    private CustomUser customer;
    private Dish dish;

    private static CustomUser withRoles(CustomUser user, UserRole... roles) {
        for (UserRole role : roles) {
            user.addRole(role);
        }
        return user;
    }

    @BeforeEach
    void setUp() {
        service = new DishService(dishRepository, dishIngredientRepository, availabilityRepository,
                locationRepository, ingredientRepository, suggestionRepository, allergicIngredientRepository,
                attachmentService, new DishNutritionService(), inventoryService, candidateFinder,
                statsProvider, userContext);

        owner = withRoles(CustomUser.builder().id(1L).username("chef").build(), UserRole.CHEF);
        stranger = withRoles(CustomUser.builder().id(2L).username("other").build(), UserRole.CHEF);
        admin = withRoles(CustomUser.builder().id(3L).username("admin").build(), UserRole.ADMIN);
        customer = withRoles(CustomUser.builder().id(4L).username("buyer").build(), UserRole.CUSTOMER);

        dish = Dish.builder()
                .uid(UUID.randomUUID())
                .name("Phở")
                .price(new BigDecimal("50000"))
                .owner(owner)
                .build();

        when(statsProvider.soldCountByDish(any())).thenReturn(Map.of());
        when(availabilityRepository.findTodayAvailability(any(), any())).thenReturn(List.of());
    }

    // =====================================================================
    // Ownership (Django's @require_object_permission)
    // =====================================================================

    @Test
    void assertCanModify_allowsOwnerAndAdmin_deniesEveryoneElse() {
        assertThatCode(() -> service.assertCanModify(dish, owner)).doesNotThrowAnyException();
        assertThatCode(() -> service.assertCanModify(dish, admin)).doesNotThrowAnyException();

        assertThatThrownBy(() -> service.assertCanModify(dish, stranger))
                .isInstanceOf(DishPermissionDeniedException.class);
        assertThatThrownBy(() -> service.assertCanModify(dish, null))
                .isInstanceOf(DishPermissionDeniedException.class);

        // An ownerless dish is only modifiable by an ADMIN.
        Dish orphan = Dish.builder().uid(UUID.randomUUID()).name("x").price(BigDecimal.ONE).build();
        assertThatThrownBy(() -> service.assertCanModify(orphan, owner))
                .isInstanceOf(DishPermissionDeniedException.class);
        assertThatCode(() -> service.assertCanModify(orphan, admin)).doesNotThrowAnyException();
    }

    /**
     * The model-permission half of {@code @require_object_permission('dish.change_dish', ...)}:
     * CUSTOMER holds only {@code dish.view_dish}, so it fails before ownership is
     * even consulted — even for a dish it somehow owns. Before the real role model
     * was ported this branch did not exist (the check was {@code isStaff} + owner).
     */
    @Test
    void assertCanModify_deniesACustomerEvenWhenTheyOwnTheDish() {
        Dish customerOwned = Dish.builder().uid(UUID.randomUUID()).name("y").price(BigDecimal.ONE)
                .owner(customer).build();
        assertThatThrownBy(() -> service.assertCanModify(customerOwned, customer))
                .isInstanceOf(DishPermissionDeniedException.class);
    }

    /** Django's ModelBackend: an INACTIVE user has no permissions at all. */
    @Test
    void assertCanModify_deniesAnInactiveOwner() {
        owner.setActive(false);
        assertThatThrownBy(() -> service.assertCanModify(dish, owner))
                .isInstanceOf(DishPermissionDeniedException.class);
    }

    @Test
    void getDishEntity_missing_throwsDishNotFound() {
        when(dishRepository.findByUidAndDeletedFalse(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getDishEntity(UUID.randomUUID()))
                .isInstanceOf(DishNotFoundException.class)
                .hasMessage("Dish not found");
    }

    // =====================================================================
    // Preserved Django quirks
    // =====================================================================

    @Test
    void softDeleteDish_alwaysSucceeds_becauseDjangosReferenceGuardIsCommentedOut() {
        // PORT-NOTE regression test: DishORM.soft_delete_dish has its
        // has_related_objects() check commented out, so DishIsReferenced can never be
        // raised from this path — even for a dish other rows point at.
        when(dishRepository.findByUidAndDeletedFalse(dish.getUid())).thenReturn(Optional.of(dish));
        when(dishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.softDeleteDish(owner, dish.getUid())).isTrue();
        assertThat(dish.isDeleted()).isTrue();
        assertThat(dish.getUpdater()).isEqualTo(owner);
    }

    @Test
    void restoreDish_onALiveDish_throwsDishIsNotDeleted_withDjangosInheritedMessage() {
        when(dishRepository.findByUid(dish.getUid())).thenReturn(Optional.of(dish));

        assertThatThrownBy(() -> service.restoreDish(owner, dish.getUid()))
                .isInstanceOf(DishIsNotDeletedException.class)
                // PORT-NOTE: Django's DishIsNotDeleted class body never sets `message`
                // (the intended string was pasted into DishPermissionDenied instead), so
                // it inherits APIException's base default. Preserved verbatim.
                .hasMessage("Internal server error")
                .satisfies(ex -> assertThat(((DishIsNotDeletedException) ex).getMessageCode())
                        .isEqualTo("DISH_NOT_DELETED"));
    }

    @Test
    void restoreDish_looksUpDeletedDishesToo() {
        dish.setDeleted(true);
        when(dishRepository.findByUid(dish.getUid())).thenReturn(Optional.of(dish));
        when(dishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.restoreDish(owner, dish.getUid())).isTrue();
        assertThat(dish.isDeleted()).isFalse();
    }

    @Test
    void addIngredientToDish_whenAlreadyPresent_returnsTheExistingRowWithoutPersistingTheRecomputedValues() {
        // PORT-NOTE regression test: despite the endpoint declaring
        // DishIngredientAlreadyExists, Django silently returns the existing row and
        // does NOT write the freshly computed nutrition back. Preserved exactly.
        Ingredient ingredient = Ingredient.builder()
                .uid(UUID.randomUUID()).name("gạo").category(IngredientCategory.GRAIN)
                .weight(100.0).energy(130.0).protein(2.7).lipid(0.3).carbohydrate(28.0)
                .build();
        DishIngredient existing = DishIngredient.builder()
                .uid(UUID.randomUUID())
                .dish(dish)
                .ingredient(ingredient)
                .approvalStatus(IngredientImportStatus.APPROVED)
                .source(IngredientSource.USDA)
                .weight(100.0)
                .energy(130.0)
                .confidence(0.9)
                .createdBy(owner)
                .updatedBy(owner)
                .build();

        when(dishRepository.findByUidAndDeletedFalse(dish.getUid())).thenReturn(Optional.of(dish));
        when(ingredientRepository.findByUidAndDeletedFalse(ingredient.getUid())).thenReturn(Optional.of(ingredient));
        when(dishIngredientRepository.findFirstByDishAndIngredientAndDeletedFalse(dish, ingredient))
                .thenReturn(Optional.of(existing));

        DishIngredientCreateRequest payload = new DishIngredientCreateRequest(
                ingredient.getUid(), 500, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);

        DishIngredientSaveResponse response = service.addIngredientToDish(owner, dish.getUid(), payload);

        verify(dishIngredientRepository, never()).save(any());
        // The response carries the newly computed (500 g) values...
        assertThat(response.nutritions().energy()).isEqualTo(650.0);
        // ...while the stored row is untouched at its original 100 g snapshot.
        assertThat(existing.getWeight()).isEqualTo(100.0);
        assertThat(existing.getEnergy()).isEqualTo(130.0);
        assertThat(response.status()).isEqualTo("APPROVED");
    }

    @Test
    void updateDishIngredient_customRowWithoutAName_raisesDjangosNinjaValidationError() {
        DishIngredient row = DishIngredient.builder()
                .uid(UUID.randomUUID())
                .dish(dish)
                .ingredient(null)
                .customName(null)
                .source(IngredientSource.CHEF_SUGGESTION)
                .approvalStatus(IngredientImportStatus.PENDING)
                .weight(10.0)
                .build();
        when(dishIngredientRepository.findByUidAndDeletedFalse(row.getUid())).thenReturn(Optional.of(row));

        DishIngredientUpdateRequest payload = new DishIngredientUpdateRequest(
                null, "   ", null, 12, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.updateDishIngredient(owner, row.getUid(), payload))
                .isInstanceOf(DishIngredientValidationException.class)
                .satisfies(ex -> {
                    DishIngredientValidationException e = (DishIngredientValidationException) ex;
                    // CLAUDE.md §6: ninja's validation handler hardcodes 401.
                    assertThat(e.getHttpStatus().value()).isEqualTo(401);
                    assertThat(e.getMessageCode()).isEqualTo("VALIDATION_ERROR");
                    @SuppressWarnings("unchecked")
                    Map<String, List<String>> detail = (Map<String, List<String>>) e.getDetail();
                    assertThat(detail.get("custom_name"))
                            .containsExactly("custom_name la bat buoc khi ingredient_uid = null");
                });
    }

    @Test
    void updateDishIngredient_supplyingAnIngredientUid_promotesTheRowToApprovedUsda() {
        Ingredient ingredient = Ingredient.builder()
                .uid(UUID.randomUUID()).name("nếp").category(IngredientCategory.GRAIN)
                .weight(100.0).energy(100.0).protein(10.0).lipid(0.0).carbohydrate(15.0)
                .build();
        DishIngredient row = DishIngredient.builder()
                .uid(UUID.randomUUID())
                .dish(dish)
                .ingredient(null)
                .customName("nep cai hoa vang")
                .source(IngredientSource.CHEF_SUGGESTION)
                .approvalStatus(IngredientImportStatus.PENDING)
                .weight(10.0)
                .createdBy(owner)
                .build();

        when(dishIngredientRepository.findByUidAndDeletedFalse(row.getUid())).thenReturn(Optional.of(row));
        when(ingredientRepository.findById(ingredient.getUid())).thenReturn(Optional.of(ingredient));
        when(dishIngredientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DishIngredientUpdateRequest payload = new DishIngredientUpdateRequest(
                ingredient.getUid(), null, null, 200, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);

        DishIngredientSaveResponse response = service.updateDishIngredient(owner, row.getUid(), payload);

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.approvalStatus()).isEqualTo("APPROVED");
        assertThat(response.source()).isEqualTo(IngredientSource.USDA);
        assertThat(response.customName()).isNull();
        assertThat(response.suggestionUid()).isNull();
        assertThat(row.getIngredient()).isEqualTo(ingredient);
        assertThat(row.getCustomName()).isNull();
        // 200 g of a 100 g reference -> everything doubles
        assertThat(response.nutritions().energy()).isEqualTo(200.0);
        assertThat(response.nutritions().carbohydrate()).isEqualTo(30.0);
    }

    @Test
    void updateDish_withAnExplicitlyNullLocationId_clearsTheLocation() {
        // PORT-NOTE: Django applies every other field with exclude_none=True, but reads
        // location_id via hasattr(), so a null location_id genuinely means "clear it".
        com.amomeal.marketplace.dish.entity.DishLocation location =
                com.amomeal.marketplace.dish.entity.DishLocation.builder()
                        .id(7L).name("Vietnam").slug("vietnam")
                        .type(com.amomeal.marketplace.dish.entity.DishLocationType.COUNTRY)
                        .build();
        dish.setLocation(location);
        when(dishRepository.findByUidAndDeletedFalse(dish.getUid())).thenReturn(Optional.of(dish));
        when(dishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateDish(owner, dish.getUid(),
                new com.amomeal.marketplace.dish.dto.DishUpdateRequest(
                        "Phở mới", null, null, null, null, null, null, null));

        assertThat(dish.getLocation()).isNull();
        assertThat(dish.getName()).isEqualTo("Phở mới");
    }
}
