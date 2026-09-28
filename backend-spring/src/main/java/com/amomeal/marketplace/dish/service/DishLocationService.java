package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.dish.dto.DishLocationCreateRequest;
import com.amomeal.marketplace.dish.dto.DishLocationResponse;
import com.amomeal.marketplace.dish.dto.DishLocationShortResponse;
import com.amomeal.marketplace.dish.dto.DishLocationTreeResponse;
import com.amomeal.marketplace.dish.dto.DishLocationUpdateRequest;
import com.amomeal.marketplace.dish.entity.DishLocation;
import com.amomeal.marketplace.dish.entity.DishLocationType;
import com.amomeal.marketplace.dish.exception.DishLocationHasChildrenException;
import com.amomeal.marketplace.dish.exception.DishLocationNotFoundException;
import com.amomeal.marketplace.dish.repository.DishLocationRepository;
import com.amomeal.marketplace.ingredient.service.RemoveAccents;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ports the DishLocation half of
 * ../../backend/dish/services/__init__.py::DishService (create/get/list/tree/
 * update/delete) plus the model-level behavior Django puts in
 * {@code DishLocation.save()} / {@code DishLocation.clean()} — slug
 * auto-generation and the REGION -&gt; SUBREGION -&gt; COUNTRY hierarchy rules.
 * Those live here rather than on the entity because both need repository access.
 */
@Service
@RequiredArgsConstructor
public class DishLocationService {

    private final DishLocationRepository locationRepository;

    // =====================================================================
    // Slug + hierarchy validation (Django: DishLocation.save/clean)
    // =====================================================================

    /**
     * Port of {@code django.utils.text.slugify(remove_accents(name))}: strip
     * accents, lowercase, drop anything that is not alphanumeric/underscore/
     * hyphen/whitespace, then collapse whitespace and hyphen runs into single
     * hyphens and trim them off the ends. Django falls back to the literal
     * {@code "location"} when that yields an empty string.
     */
    static String slugify(String value) {
        String normalized = Normalizer.normalize(RemoveAccents.apply(value == null ? "" : value), Normalizer.Form.NFKC);
        String lowered = normalized.toLowerCase(Locale.ROOT);
        String cleaned = lowered.replaceAll("[^a-z0-9_\\-\\s]", "");
        String collapsed = cleaned.trim().replaceAll("[-\\s]+", "-");
        String trimmed = collapsed.replaceAll("^-+", "").replaceAll("-+$", "");
        return trimmed.isEmpty() ? "location" : trimmed;
    }

    /** Django: the {@code while ... exists()} suffix loop in DishLocation.save(). */
    private String uniqueSlug(String name, Long excludeId) {
        String base = slugify(name);
        String slug = base;
        int i = 2;
        while (excludeId == null ? locationRepository.existsBySlug(slug)
                : locationRepository.existsBySlugAndIdNot(slug, excludeId)) {
            slug = base + "-" + i;
            i++;
        }
        return slug;
    }

    /**
     * Django: {@code DishLocation.clean()}.
     *
     * <p>PORT-NOTE on the error type: Django raises
     * {@code django.core.exceptions.ValidationError}, which the ninja exception
     * plumbing does NOT special-case (it only handles {@code ninja.errors
     * .ValidationError}), so a hierarchy violation surfaces as a generic 500.
     * Throwing {@link IllegalArgumentException} here reproduces that exactly via
     * {@code GlobalExceptionHandler}'s catch-all, rather than inventing a 4xx
     * contract the FE has never seen.
     *
     * <p>Note the consequence of Django's {@code allowed_parent[REGION] = None}
     * entry: a REGION given a parent always fails, because no parent type can
     * equal None.
     */
    private void validateHierarchy(DishLocation location) {
        DishLocation parent = location.getParent();

        if (parent != null && location.getId() != null && parent.getId() != null
                && parent.getId().equals(location.getId())) {
            throw new IllegalArgumentException("Cannot be its own parent.");
        }

        if (parent == null) {
            if (location.getType() != DishLocationType.REGION) {
                throw new IllegalArgumentException("Only REGION can be root.");
            }
            return;
        }

        DishLocationType expected = switch (location.getType()) {
            case REGION -> null;
            case SUBREGION -> DishLocationType.REGION;
            case COUNTRY -> DishLocationType.SUBREGION;
        };

        if (parent.getType() != expected) {
            throw new IllegalArgumentException(location.getType() + " must have parent type " + expected);
        }

        // cycle check
        DishLocation ancestor = parent;
        while (ancestor != null) {
            if (location.getId() != null && location.getId().equals(ancestor.getId())) {
                throw new IllegalArgumentException("Cycle detected");
            }
            ancestor = ancestor.getParent();
        }
    }

    // =====================================================================
    // Service methods (Django: DishService.*_dish_location)
    // =====================================================================

    @Transactional
    public DishLocationResponse createDishLocation(DishLocationCreateRequest payload) {
        DishLocation parent = null;
        if (payload.parentId() != null) {
            parent = locationRepository.findById(payload.parentId())
                    .orElseThrow(DishLocationNotFoundException::new);
        }
        DishLocation location = DishLocation.builder()
                .name(payload.name())
                .type(payload.type())
                .parent(parent)
                .build();
        location.setSlug(uniqueSlug(payload.name(), null));
        validateHierarchy(location);
        return DishLocationResponse.from(locationRepository.save(location));
    }

    @Transactional(readOnly = true)
    public DishLocation getEntityById(Long locationId) {
        return locationRepository.findById(locationId).orElseThrow(DishLocationNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public DishLocationResponse getDishLocationById(Long locationId) {
        return DishLocationResponse.from(getEntityById(locationId));
    }

    /** Django: {@code get_country_locations}. */
    @Transactional(readOnly = true)
    public List<DishLocationShortResponse> getCountryLocations() {
        return locationRepository.findAllByTypeOrderByNameAsc(DishLocationType.COUNTRY).stream()
                .map(DishLocationShortResponse::from)
                .toList();
    }

    /**
     * Django: {@code get_dish_locations} — a null {@code parent_id} means "root
     * level only" ({@code parent__isnull=True}), not "any parent".
     */
    @Transactional(readOnly = true)
    public List<DishLocationResponse> getDishLocations(Long parentId, DishLocationType type) {
        List<DishLocation> locations;
        if (parentId == null) {
            locations = type == null
                    ? locationRepository.findAllByParentIsNullOrderByNameAsc()
                    : locationRepository.findAllByParentIsNullAndTypeOrderByNameAsc(type);
        } else {
            locations = type == null
                    ? locationRepository.findAllByParentIdOrderByNameAsc(parentId)
                    : locationRepository.findAllByParentIdAndTypeOrderByNameAsc(parentId, type);
        }
        return locations.stream().map(DishLocationResponse::from).toList();
    }

    /** Django: {@code get_dish_location_tree} — one query, grouped by parent, then recursive build. */
    @Transactional(readOnly = true)
    public List<DishLocationTreeResponse> getDishLocationTree() {
        List<DishLocation> locations = locationRepository.findAllByOrderByNameAsc();

        Map<Long, List<DishLocation>> byParent = new LinkedHashMap<>();
        for (DishLocation location : locations) {
            Long parentId = location.getParent() == null ? null : location.getParent().getId();
            byParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(location);
        }
        return buildTree(byParent, null);
    }

    private List<DishLocationTreeResponse> buildTree(Map<Long, List<DishLocation>> byParent, Long parentId) {
        List<DishLocationTreeResponse> nodes = new ArrayList<>();
        for (DishLocation location : byParent.getOrDefault(parentId, List.of())) {
            nodes.add(new DishLocationTreeResponse(
                    location.getId(),
                    location.getName(),
                    location.getSlug(),
                    location.getType(),
                    location.getParent() == null ? null : location.getParent().getId(),
                    buildTree(byParent, location.getId())));
        }
        return nodes;
    }

    /**
     * Django: {@code update_dish_location}. Note a null {@code parent_id} leaves
     * the parent unchanged (unlike dish update, where null clears the location).
     */
    @Transactional
    public DishLocationResponse updateDishLocation(Long locationId, DishLocationUpdateRequest payload) {
        DishLocation location = getEntityById(locationId);

        if (payload.parentId() != null) {
            DishLocation parent = locationRepository.findById(payload.parentId())
                    .orElseThrow(DishLocationNotFoundException::new);
            location.setParent(parent);
        }
        if (payload.name() != null) {
            location.setName(payload.name());
        }
        if (payload.type() != null) {
            location.setType(payload.type());
        }
        // Django re-runs save(), which only regenerates the slug when it is blank —
        // an existing slug is therefore kept even if the name changed. Preserved.
        if (location.getSlug() == null || location.getSlug().isBlank()) {
            location.setSlug(uniqueSlug(location.getName(), location.getId()));
        }
        validateHierarchy(location);
        return DishLocationResponse.from(locationRepository.save(location));
    }

    /** Django: {@code delete_dish_location}. */
    @Transactional
    public boolean deleteDishLocation(Long locationId) {
        DishLocation location = getEntityById(locationId);
        if (locationRepository.existsByParent(location)) {
            throw new DishLocationHasChildrenException();
        }
        locationRepository.delete(location);
        return true;
    }
}
