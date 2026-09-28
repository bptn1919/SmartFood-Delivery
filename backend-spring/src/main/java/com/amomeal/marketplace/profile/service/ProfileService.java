package com.amomeal.marketplace.profile.service;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.entity.AttachmentType;
import com.amomeal.marketplace.attachment.exception.AttachmentNotFoundException;
import com.amomeal.marketplace.attachment.service.AttachmentService;
import com.amomeal.marketplace.profile.dto.ChefProfileDetailRequest;
import com.amomeal.marketplace.profile.dto.ChefProfileDetailResponse;
import com.amomeal.marketplace.profile.dto.ChefProfilePage;
import com.amomeal.marketplace.profile.dto.ChefProfilePublicResponse;
import com.amomeal.marketplace.profile.dto.ChefProfileUpdateRequest;
import com.amomeal.marketplace.profile.entity.ChefPaymentInfo;
import com.amomeal.marketplace.profile.entity.ChefProfile;
import com.amomeal.marketplace.profile.exception.PermissionDeniedException;
import com.amomeal.marketplace.profile.exception.ProfileDoesNotExistException;
import com.amomeal.marketplace.profile.repository.ChefPaymentInfoRepository;
import com.amomeal.marketplace.profile.repository.ChefProfileRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Mirrors ../../backend/profile/services/__init__.py::ProfileService — the
 * CHEF-side of the module (../../backend/profile/orm/profile.py::ProfileORM's
 * chef-profile half).
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final ChefProfileRepository chefProfileRepository;
    private final ChefPaymentInfoRepository chefPaymentInfoRepository;
    private final AttachmentService attachmentService;
    private final ChefCertificationProvider certificationProvider;
    private final ChefPaymentService chefPaymentService;

    /**
     * Mirrors {@code ProfileORM.create_chef_profile} — idempotent
     * get-or-create-then-overlay, used both by {@code POST /api/chef-profiles/}
     * and the CUSTOMER→CHEF upgrade flow.
     */
    @Transactional
    public ChefProfile createChefProfile(CustomUser user, ChefProfileDetailRequest payload) {
        Attachment attachment = resolveAvatar(payload.avatar());

        ChefProfile profile = chefProfileRepository.findByUser(user).orElseGet(() -> ChefProfile.builder()
                .user(user)
                .build());

        applyChefFields(profile, payload);
        if (attachment != null) {
            profile.setAvatar(attachment);
        }
        return chefProfileRepository.save(profile);
    }

    /** Convenience overload returning the full detail response, for this module's own controller. */
    @Transactional
    public ChefProfileDetailResponse createChefProfileResponse(CustomUser user, ChefProfileDetailRequest payload) {
        ChefProfile profile = createChefProfile(user, payload);
        return toDetailResponse(profile);
    }

    private void applyChefFields(ChefProfile profile, ChefProfileDetailRequest payload) {
        if (payload.bio() != null) {
            profile.setBio(payload.bio());
        }
        if (payload.specialty() != null) {
            profile.setSpecialty(payload.specialty());
        }
        if (payload.kitchenAddress() != null) {
            profile.setKitchenAddress(payload.kitchenAddress());
        }
        if (payload.kitchenStreet() != null) {
            profile.setKitchenStreet(payload.kitchenStreet());
        }
        if (payload.kitchenWard() != null) {
            profile.setKitchenWard(payload.kitchenWard());
        }
        if (payload.kitchenDistrict() != null) {
            profile.setKitchenDistrict(payload.kitchenDistrict());
        }
        if (payload.kitchenCity() != null) {
            profile.setKitchenCity(payload.kitchenCity());
        }
        if (payload.kitchenLatitude() != null) {
            profile.setKitchenLatitude(payload.kitchenLatitude());
        }
        if (payload.kitchenLongitude() != null) {
            profile.setKitchenLongitude(payload.kitchenLongitude());
        }
        if (payload.isAcceptingOrders() != null) {
            profile.setAcceptingOrders(payload.isAcceptingOrders());
        }
        if (payload.suspensionLevel() != null) {
            profile.setSuspensionLevel(payload.suspensionLevel());
        }
    }

    private Attachment resolveAvatar(java.util.UUID avatarUid) {
        if (avatarUid == null) {
            return null;
        }
        return attachmentService.handleAttachment(avatarUid);
    }

    /** Mirrors {@code ProfileService.get_chef_public_profile}. */
    @Transactional(readOnly = true)
    public ChefProfilePublicResponse getChefPublicProfile(Long chefId) {
        ChefProfile profile = chefProfileRepository.findByUserId(chefId)
                .orElseThrow(ProfileDoesNotExistException::new);
        return ChefProfilePublicResponse.of(profile, certificationProvider.isFoodSafetyCertified(chefId));
    }

    /**
     * Mirrors {@code ProfileService.get_chef_profile} — admin-or-owner check.
     * Currently only ever called with {@code chefId == user.getId()} (the
     * {@code /me} endpoint), so the PermissionDenied branch is unreachable in
     * practice today, same as Django; ported anyway for fidelity.
     */
    @Transactional(readOnly = true)
    public ChefProfileDetailResponse getChefProfile(CustomUser user, Long chefId) {
        ChefProfile profile = chefProfileRepository.findByUserId(chefId)
                .orElseThrow(ProfileDoesNotExistException::new);
        boolean isAdmin = user.hasRole(UserRole.ADMIN);
        boolean isOwner = user.getId().equals(profile.getUser().getId());
        if (!isAdmin && !isOwner) {
            throw new PermissionDeniedException();
        }
        return toDetailResponse(profile);
    }

    /** Mirrors {@code ProfileService.get_all_chef_profiles}. */
    @Transactional(readOnly = true)
    public ChefProfilePage getAllChefProfiles(String sortBy, int page) {
        Sort sort = switch (sortBy == null ? "" : sortBy) {
            case "orders_desc" -> Sort.by(Sort.Direction.DESC, "numberOfOrders");
            case "orders_asc" -> Sort.by(Sort.Direction.ASC, "numberOfOrders");
            case "name_desc" -> Sort.by(Sort.Direction.DESC, "user.firstName");
            case "name_asc" -> Sort.by(Sort.Direction.ASC, "user.firstName");
            case "rating_asc" -> Sort.by(Sort.Direction.ASC, "rating");
            default -> Sort.by(Sort.Direction.DESC, "rating");
        };
        long total = chefProfileRepository.count();
        // Mirrors Django's `if not profile: raise ProfileDoesNotExist` — the
        // queryset is evaluated for truthiness BEFORE ninja's @paginate slices
        // it, so only a genuinely empty table 404s; an out-of-range page on a
        // non-empty table does not.
        if (total == 0) {
            throw new ProfileDoesNotExistException();
        }
        int pageIndex = Math.max(page - 1, 0);
        var slice = chefProfileRepository.findAll(PageRequest.of(pageIndex, 20, sort));
        List<ChefProfilePublicResponse> items = slice.getContent().stream()
                .map(p -> ChefProfilePublicResponse.of(p, certificationProvider.isFoodSafetyCertified(p.getUser().getId())))
                .toList();
        return new ChefProfilePage(items, total);
    }

    /** Mirrors {@code ProfileService.get_popular_chefs} (limit 5, rating desc, empty list if none). */
    @Transactional(readOnly = true)
    public List<ChefProfilePublicResponse> getPopularChefs() {
        var slice = chefProfileRepository.findAll(PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "rating")));
        return slice.getContent().stream()
                .map(p -> ChefProfilePublicResponse.of(p, certificationProvider.isFoodSafetyCertified(p.getUser().getId())))
                .toList();
    }

    /** Mirrors {@code ProfileService.update_chef_profile}. */
    @Transactional
    public ChefProfileDetailResponse updateChefProfile(CustomUser user, ChefProfileUpdateRequest payload) {
        Attachment attachment = resolveAvatar(payload.attachmentUid());
        ChefProfile profile = chefProfileRepository.findByUser(user).orElseThrow(ProfileDoesNotExistException::new);
        if (payload.bio() != null) {
            profile.setBio(payload.bio());
        }
        if (payload.specialty() != null) {
            profile.setSpecialty(payload.specialty());
        }
        if (attachment != null) {
            profile.setAvatar(attachment);
        }
        ChefProfile saved = chefProfileRepository.save(profile);
        return toDetailResponse(saved);
    }

    /** Mirrors {@code ChefProfileController.check_is_chef} (GET /is-chef-id). */
    @Transactional(readOnly = true)
    public com.amomeal.marketplace.profile.dto.CheckChefResponse checkIsChef(CustomUser user) {
        return chefProfileRepository.findByUser(user)
                .map(p -> new com.amomeal.marketplace.profile.dto.CheckChefResponse(true, p.getId().intValue()))
                .orElseGet(() -> new com.amomeal.marketplace.profile.dto.CheckChefResponse(false, null));
    }

    /** Exposed for {@link UpgradeToChefService}, which needs to build the response after its own transaction steps. */
    public ChefProfileDetailResponse toDetailResponse(ChefProfile profile) {
        ChefPaymentInfo bankInfo = chefPaymentInfoRepository.findByUser(profile.getUser()).orElse(null);
        var bankResponse = bankInfo == null ? null
                : com.amomeal.marketplace.profile.dto.ChefPaymentInfoResponse.of(bankInfo,
                        chefPaymentService.maskAccountNumber(bankInfo.getBankAccountNumber()));
        boolean certified = certificationProvider.isFoodSafetyCertified(profile.getUser().getId());
        return ChefProfileDetailResponse.of(profile, certified, bankResponse);
    }
}
