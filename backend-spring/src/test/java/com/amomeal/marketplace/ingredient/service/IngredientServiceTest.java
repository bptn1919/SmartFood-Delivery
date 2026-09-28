package com.amomeal.marketplace.ingredient.service;

import com.amomeal.marketplace.ingredient.dto.IngredientRequest;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.amomeal.marketplace.ingredient.exception.IngredientDoesNotExistException;
import com.amomeal.marketplace.ingredient.exception.IngredientIsNotDeletedException;
import com.amomeal.marketplace.ingredient.exception.IngredientIsReferencedException;
import com.amomeal.marketplace.ingredient.exception.IngredientNameAlreadyExistsException;
import com.amomeal.marketplace.ingredient.repository.AllergicIngredientRepository;
import com.amomeal.marketplace.ingredient.repository.FavouriteIngredientRepository;
import com.amomeal.marketplace.ingredient.repository.IngredientAliasRepository;
import com.amomeal.marketplace.ingredient.repository.IngredientRepository;
import com.amomeal.marketplace.ingredient.repository.IngredientSuggestionRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for IngredientService's business logic (mirrors
 * ../../backend/ingredient/services/__init__.py's
 * IngredientQueryService/IngredientCommandService + IngredientORM's
 * alias/soft-delete/restore behavior), with repositories mocked.
 */
@ExtendWith(MockitoExtension.class)
class IngredientServiceTest {

    @Mock
    private IngredientRepository ingredientRepository;
    @Mock
    private IngredientAliasRepository aliasRepository;
    @Mock
    private IngredientSuggestionRepository suggestionRepository;
    @Mock
    private FavouriteIngredientRepository favouriteIngredientRepository;
    @Mock
    private AllergicIngredientRepository allergicIngredientRepository;
    @Mock
    private CustomUserRepository customUserRepository;

    private IngredientService ingredientService;

    private CustomUser owner;

    @BeforeEach
    void setUp() {
        ingredientService = new IngredientService(ingredientRepository, aliasRepository, suggestionRepository,
                favouriteIngredientRepository, allergicIngredientRepository, customUserRepository);
        owner = CustomUser.builder().id(1L).username("admin").email("admin@amomeal.test").password("x").build();
    }

    private IngredientRequest sampleRequest(String name) {
        return new IngredientRequest(name, IngredientCategory.VEGETABLE, 100.0, 41.0, 0.9, 0.2, 9.6, 2.8,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void createNewIngredient_savesNormalizedNameAndOwner() {
        when(ingredientRepository.existsByNameIgnoreCaseAndDeletedFalse("ca rot")).thenReturn(false);
        when(customUserRepository.getReferenceById(1L)).thenReturn(owner);
        when(ingredientRepository.save(any(Ingredient.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = ingredientService.createNewIngredient(1L, sampleRequest("  Ca   Rot  "));

        assertThat(response.name()).isEqualTo("ca rot");
        assertThat(response.category()).isEqualTo(IngredientCategory.VEGETABLE);
        verify(ingredientRepository).save(argThat(i -> i.getOwner() == owner && i.getUpdater() == owner));
    }

    @Test
    void createNewIngredient_throwsWhenDuplicateName() {
        when(ingredientRepository.existsByNameIgnoreCaseAndDeletedFalse("ca rot")).thenReturn(true);

        assertThatThrownBy(() -> ingredientService.createNewIngredient(1L, sampleRequest("Ca Rot")))
                .isInstanceOf(IngredientNameAlreadyExistsException.class);
        verify(ingredientRepository, never()).save(any());
    }

    @Test
    void getIngredientResponseByUid_throwsWhenMissing() {
        UUID uid = UUID.randomUUID();
        when(ingredientRepository.findByUidAndDeletedFalse(uid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ingredientService.getIngredientResponseByUid(uid))
                .isInstanceOf(IngredientDoesNotExistException.class);
    }

    @Test
    void softDeleteIngredient_throwsWhenReferencedByAlias() {
        Ingredient ingredient = ingredientOf(UUID.randomUUID(), "Ca Rot");
        when(ingredientRepository.findByUidAndDeletedFalse(ingredient.getUid())).thenReturn(Optional.of(ingredient));
        when(aliasRepository.existsByIngredient(ingredient)).thenReturn(true);

        assertThatThrownBy(() -> ingredientService.softDeleteIngredient(1L, ingredient.getUid()))
                .isInstanceOf(IngredientIsReferencedException.class);
        verify(ingredientRepository, never()).save(any());
    }

    @Test
    void softDeleteIngredient_succeedsWhenUnreferenced() {
        Ingredient ingredient = ingredientOf(UUID.randomUUID(), "Ca Rot");
        when(ingredientRepository.findByUidAndDeletedFalse(ingredient.getUid())).thenReturn(Optional.of(ingredient));
        when(aliasRepository.existsByIngredient(ingredient)).thenReturn(false);
        when(favouriteIngredientRepository.existsByIngredient(ingredient)).thenReturn(false);
        when(allergicIngredientRepository.existsByIngredient(ingredient)).thenReturn(false);
        when(suggestionRepository.existsByIngredient(ingredient)).thenReturn(false);
        when(customUserRepository.getReferenceById(1L)).thenReturn(owner);

        boolean result = ingredientService.softDeleteIngredient(1L, ingredient.getUid());

        assertThat(result).isTrue();
        assertThat(ingredient.isDeleted()).isTrue();
        verify(ingredientRepository).save(ingredient);
    }

    @Test
    void hardDeleteIngredient_wrapsIntegrityViolation() {
        Ingredient ingredient = ingredientOf(UUID.randomUUID(), "Ca Rot");
        when(ingredientRepository.findByUidAndDeletedFalse(ingredient.getUid())).thenReturn(Optional.of(ingredient));
        doThrow(new DataIntegrityViolationException("fk violation")).when(ingredientRepository).delete(ingredient);

        assertThatThrownBy(() -> ingredientService.hardDeleteIngredient(ingredient.getUid()))
                .isInstanceOf(IngredientIsReferencedException.class);
    }

    /**
     * PORT-NOTE regression test: Django's restore_ingredient can never
     * actually succeed (see IngredientService#restoreIngredient javadoc) —
     * looking the ingredient up via the deleted=false-filtered finder means a
     * genuinely deleted ingredient always 404s here, exactly like Django.
     */
    @Test
    void restoreIngredient_alwaysNotFound_becauseLookupFiltersDeletedFalse() {
        UUID uid = UUID.randomUUID();
        when(ingredientRepository.findByUidAndDeletedFalse(uid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ingredientService.restoreIngredient(1L, uid))
                .isInstanceOf(IngredientDoesNotExistException.class);
    }

    @Test
    void restoreIngredient_throwsIsNotDeletedWhenFound() {
        Ingredient ingredient = ingredientOf(UUID.randomUUID(), "Ca Rot");
        ingredient.setDeleted(false);
        when(ingredientRepository.findByUidAndDeletedFalse(ingredient.getUid())).thenReturn(Optional.of(ingredient));

        assertThatThrownBy(() -> ingredientService.restoreIngredient(1L, ingredient.getUid()))
                .isInstanceOf(IngredientIsNotDeletedException.class);
    }

    private Ingredient ingredientOf(UUID uid, String name) {
        return Ingredient.builder().uid(uid).name(name).nameNoAccent(RemoveAccents.apply(name))
                .category(IngredientCategory.VEGETABLE).deleted(false).build();
    }
}
