package com.amomeal.marketplace.ingredient.service;

import com.amomeal.marketplace.ingredient.dto.*;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.entity.IngredientAlias;
import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.amomeal.marketplace.ingredient.entity.IngredientImportStatus;
import com.amomeal.marketplace.ingredient.entity.IngredientSuggestion;
import com.amomeal.marketplace.ingredient.exception.IngredientDoesNotExistException;
import com.amomeal.marketplace.ingredient.exception.IngredientIsNotPendingException;
import com.amomeal.marketplace.ingredient.exception.IngredientSuggestionNotFoundException;
import com.amomeal.marketplace.ingredient.repository.IngredientRepository;
import com.amomeal.marketplace.ingredient.repository.IngredientSuggestionRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/ingredient/services/__init__.py::IngredientSuggestionService
 * — the chef-facing moderation queue for new ingredient names.
 *
 * <p>PORT-NOTE (dish dependency, flagged in PROGRESS.md): in Django, a
 * suggestion is created internally by the {@code dish} app (a chef typing a
 * custom ingredient name while building a dish) and {@code approve_new}/
 * {@code approve_alias} pull nutrition data from the originating
 * {@code DishIngredient} row, then push the resolution back onto every
 * {@code DishIngredient} referencing that suggestion. `dish` isn't ported yet
 * (ingredient ports first per CLAUDE.md §7). This port therefore:
 * <ul>
 *   <li>adds {@link #createSuggestion} directly on this controller (see
 *   {@link com.amomeal.marketplace.ingredient.dto.IngredientSuggestionCreateRequest}
 *   javadoc) so the queue is usable/testable now;</li>
 *   <li>{@link #approveNew} creates a bare new {@link Ingredient} from the
 *   suggestion's name/category (no nutrition data — there is no
 *   DishIngredient to scale from yet), skipping Django's
 *   {@code DishIngredientNotFoundException} requirement entirely;</li>
 *   <li>{@link #approveAlias} and {@link #reject} perform the full
 *   ingredient/alias-side resolution but skip the {@code DishIngredient}
 *   sync loop (nothing to sync against yet).</li>
 * </ul>
 * Revisit all three once `dish` is ported.
 */
@Service
@RequiredArgsConstructor
public class IngredientSuggestionService {

    private final IngredientSuggestionRepository suggestionRepository;
    private final IngredientRepository ingredientRepository;
    private final IngredientService ingredientService;
    private final CustomUserRepository customUserRepository;

    /** PORT-NOTE: no Django equivalent endpoint — see class javadoc. */
    @Transactional
    public IngredientSuggestionResponse createSuggestion(CustomUser user, IngredientSuggestionCreateRequest payload) {
        IngredientSuggestion existing = suggestionRepository
                .findFirstBySuggestedNameIgnoreCaseAndStatusAndCreatedBy(payload.customName(), IngredientImportStatus.PENDING, user)
                .orElse(null);
        if (existing != null) {
            return IngredientSuggestionResponse.from(existing);
        }
        String name = payload.customName().strip();
        IngredientSuggestion suggestion = IngredientSuggestion.builder()
                .suggestedName(name)
                .suggestedCategory(payload.category())
                .createdBy(user)
                .status(IngredientImportStatus.PENDING)
                .build();
        suggestion = suggestionRepository.save(suggestion);
        return IngredientSuggestionResponse.from(suggestion);
    }

    @Transactional(readOnly = true)
    public PageResponse<IngredientSuggestionResponse> listSuggestions(
            String status, String search, IngredientCategory category, String orderBy, String sortType,
            Long createdById, int page, int pageSize) {
        IngredientImportStatus statusEnum = parseStatusOrNull(status);
        CustomUser createdBy = createdById == null ? null : customUserRepository.getReferenceById(createdById);
        Specification<IngredientSuggestion> spec = IngredientSuggestionSpecifications.filter(statusEnum, search, category, createdBy);
        Pageable pageable = IngredientService.toPageable(page, pageSize, resolveSuggestionSort(orderBy, sortType));
        var result = suggestionRepository.findAll(spec, pageable);
        List<IngredientSuggestionResponse> content = result.getContent().stream().map(IngredientSuggestionResponse::from).toList();
        return PageResponse.of(content, page, pageSize, result.getTotalElements());
    }

    @Transactional
    public boolean softDeleteSuggestion(CustomUser user, UUID uid) {
        IngredientSuggestion suggestion = suggestionRepository.findByUidAndCreatedBy(uid, user)
                .orElseThrow(IngredientSuggestionNotFoundException::new);
        if (suggestion.getStatus() != IngredientImportStatus.PENDING) {
            throw new IngredientIsNotPendingException();
        }
        suggestion.setDeleted(true);
        suggestionRepository.save(suggestion);
        return true;
    }

    /** PORT-NOTE: simplified — see class javadoc. */
    @Transactional
    public IngredientSuggestionApproveNewResponse approveNew(CustomUser user, UUID uid, IngredientSuggestionApproveNewRequest payload) {
        IngredientSuggestion suggestion = suggestionRepository.findById(uid)
                .orElseThrow(IngredientSuggestionNotFoundException::new);

        Ingredient newIngredient = Ingredient.builder()
                .name(suggestion.getSuggestedName())
                .category(suggestion.getSuggestedCategory())
                .owner(user)
                .updater(user)
                .build();
        newIngredient = ingredientRepository.save(newIngredient);

        suggestion.setIngredient(newIngredient);
        suggestion.setStatus(IngredientImportStatus.APPROVED);
        suggestion.setVerifiedBy(user);
        suggestion.setVerifiedAt(Instant.now());
        suggestion.setResolutionNote(payload.resolutionNote());
        suggestion = suggestionRepository.save(suggestion);

        return new IngredientSuggestionApproveNewResponse(
                suggestion.getUid(), suggestion.getSuggestedName(), suggestion.getSuggestedCategory(),
                suggestion.getStatus(), newIngredient.getUid(),
                suggestion.getCreatedBy() == null ? null : suggestion.getCreatedBy().getId(),
                suggestion.getVerifiedBy().getId(), suggestion.getVerifiedAt().toString(), suggestion.getResolutionNote());
    }

    /** PORT-NOTE: dish-sync loop skipped — see class javadoc. */
    @Transactional
    public IngredientSuggestionApproveAliasResponse approveAlias(CustomUser user, UUID uid, IngredientSuggestionApproveAliasRequest payload) {
        IngredientSuggestion suggestion = suggestionRepository.findById(uid)
                .orElseThrow(IngredientSuggestionNotFoundException::new);
        Ingredient ingredient = ingredientRepository.findByUidAndDeletedFalse(payload.ingredientUid())
                .orElseThrow(IngredientDoesNotExistException::new);

        IngredientAlias alias = ingredientService.upsertAlias(ingredient, suggestion.getSuggestedName(), user);

        suggestion.setIngredient(ingredient);
        suggestion.setResolvedAlias(alias);
        suggestion.setStatus(IngredientImportStatus.APPROVED);
        suggestion.setVerifiedBy(user);
        suggestion.setVerifiedAt(Instant.now());
        suggestion.setRejectionReason(null);
        suggestion.setResolutionNote(payload.resolutionNote());
        suggestion = suggestionRepository.save(suggestion);

        return new IngredientSuggestionApproveAliasResponse(
                suggestion.getUid(), suggestion.getSuggestedName(), suggestion.getSuggestedCategory(),
                suggestion.getStatus(), ingredient.getUid(), alias.getUid(),
                suggestion.getCreatedBy() == null ? null : suggestion.getCreatedBy().getId(),
                suggestion.getVerifiedBy().getId(), suggestion.getVerifiedAt().toString(), suggestion.getResolutionNote());
    }

    /** PORT-NOTE: dish-confidence-recompute loop skipped — see class javadoc. */
    @Transactional
    public IngredientSuggestionRejectResponse reject(CustomUser user, UUID uid, String rejectionReason) {
        IngredientSuggestion suggestion = suggestionRepository.findById(uid)
                .orElseThrow(IngredientSuggestionNotFoundException::new);

        suggestion.setStatus(IngredientImportStatus.REJECTED);
        suggestion.setVerifiedBy(user);
        suggestion.setVerifiedAt(Instant.now());
        suggestion.setRejectionReason(rejectionReason);
        suggestion.setResolutionNote(rejectionReason);
        suggestion = suggestionRepository.save(suggestion);

        return new IngredientSuggestionRejectResponse(
                suggestion.getUid(), suggestion.getSuggestedName(), suggestion.getSuggestedCategory(),
                suggestion.getStatus(), rejectionReason,
                suggestion.getCreatedBy() == null ? null : suggestion.getCreatedBy().getId(),
                suggestion.getVerifiedBy().getId(), suggestion.getVerifiedAt().toString());
    }

    private IngredientImportStatus parseStatusOrNull(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        try {
            return IngredientImportStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Sort resolveSuggestionSort(String orderBy, String sortType) {
        String field = switch (orderBy == null ? "" : orderBy) {
            case "updated_at" -> "updatedAt";
            case "verified_at" -> "verifiedAt";
            default -> "createdAt";
        };
        Sort.Direction direction = "asc".equalsIgnoreCase(sortType) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direction, field);
    }
}
