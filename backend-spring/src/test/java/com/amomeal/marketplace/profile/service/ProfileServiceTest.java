package com.amomeal.marketplace.profile.service;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.service.AttachmentService;
import com.amomeal.marketplace.profile.dto.ChefProfileDetailRequest;
import com.amomeal.marketplace.profile.dto.ChefProfilePage;
import com.amomeal.marketplace.profile.entity.ChefProfile;
import com.amomeal.marketplace.profile.entity.ChefSuspensionLevel;
import com.amomeal.marketplace.profile.exception.PermissionDeniedException;
import com.amomeal.marketplace.profile.exception.ProfileDoesNotExistException;
import com.amomeal.marketplace.profile.repository.ChefPaymentInfoRepository;
import com.amomeal.marketplace.profile.repository.ChefProfileRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests (Mockito) for {@link ProfileService} — the CHEF-side of the
 * module: idempotent create-or-overlay semantics (Django's
 * {@code get_or_create} + field loop), owner/admin gating, and the
 * "empty table 404s, empty page doesn't" pagination quirk.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProfileServiceTest {

    @Mock private ChefProfileRepository chefProfileRepository;
    @Mock private ChefPaymentInfoRepository chefPaymentInfoRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private ChefCertificationProvider certificationProvider;
    @Mock private ChefPaymentService chefPaymentService;

    private ProfileService service;
    private CustomUser chef;

    @BeforeEach
    void setUp() {
        service = new ProfileService(chefProfileRepository, chefPaymentInfoRepository, attachmentService,
                certificationProvider, chefPaymentService);
        chef = CustomUser.builder().id(1L).username("chef").firstName("A").lastName("B").build();
        when(chefPaymentInfoRepository.findByUser(any())).thenReturn(Optional.empty());
    }

    @Test
    void createChefProfile_newUser_appliesProvidedFieldsAndModelDefaults() {
        when(chefProfileRepository.findByUser(chef)).thenReturn(Optional.empty());
        when(chefProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ChefProfileDetailRequest req = new ChefProfileDetailRequest(null, "Xin chào", null, "123 Le Loi", null, null,
                null, null, null, null, null, null);
        ChefProfile saved = service.createChefProfile(chef, req);

        assertThat(saved.getBio()).isEqualTo("Xin chào");
        assertThat(saved.getKitchenAddress()).isEqualTo("123 Le Loi");
        // Untouched fields keep the Java/Builder defaults, matching Django's
        // model-field defaults for a fresh row.
        assertThat(saved.isAcceptingOrders()).isTrue();
        assertThat(saved.getSuspensionLevel()).isEqualTo(ChefSuspensionLevel.NONE);
    }

    @Test
    void createChefProfile_idempotentOnExisting_onlyOverlaysProvidedFields() {
        ChefProfile existing = ChefProfile.builder().id(5L).user(chef).bio("old bio").specialty("old specialty")
                .build();
        when(chefProfileRepository.findByUser(chef)).thenReturn(Optional.of(existing));
        when(chefProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ChefProfileDetailRequest req = new ChefProfileDetailRequest(null, "new bio", null, null, null, null, null,
                null, null, null, null, null);
        ChefProfile saved = service.createChefProfile(chef, req);

        assertThat(saved.getId()).isEqualTo(5L);
        assertThat(saved.getBio()).isEqualTo("new bio");
        // specialty wasn't in this payload -> left unchanged, not reset.
        assertThat(saved.getSpecialty()).isEqualTo("old specialty");
    }

    @Test
    void createChefProfile_withAvatar_resolvesThroughAttachmentService() {
        UUID avatarUid = UUID.randomUUID();
        Attachment attachment = Attachment.builder().uid(avatarUid).build();
        when(attachmentService.handleAttachment(avatarUid)).thenReturn(attachment);
        when(chefProfileRepository.findByUser(chef)).thenReturn(Optional.empty());
        when(chefProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ChefProfileDetailRequest req = new ChefProfileDetailRequest(avatarUid, null, null, null, null, null, null,
                null, null, null, null, null);
        ChefProfile saved = service.createChefProfile(chef, req);

        assertThat(saved.getAvatar()).isSameAs(attachment);
    }

    @Test
    void getChefPublicProfile_notFound_throwsProfileDoesNotExist() {
        when(chefProfileRepository.findByUserId(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getChefPublicProfile(99L)).isInstanceOf(ProfileDoesNotExistException.class);
    }

    @Test
    void getChefProfile_owner_succeeds() {
        ChefProfile profile = ChefProfile.builder().id(1L).user(chef).build();
        when(chefProfileRepository.findByUserId(chef.getId())).thenReturn(Optional.of(profile));
        var response = service.getChefProfile(chef, chef.getId());
        assertThat(response.userId()).isEqualTo(chef.getId().intValue());
    }

    @Test
    void getChefProfile_adminNotOwner_succeeds() {
        CustomUser admin = CustomUser.builder().id(2L).username("admin").build();
        admin.addRole(UserRole.ADMIN);
        ChefProfile profile = ChefProfile.builder().id(1L).user(chef).build();
        when(chefProfileRepository.findByUserId(chef.getId())).thenReturn(Optional.of(profile));

        var response = service.getChefProfile(admin, chef.getId());
        assertThat(response.userId()).isEqualTo(chef.getId().intValue());
    }

    @Test
    void getChefProfile_strangerNotOwnerNorAdmin_isDenied() {
        CustomUser stranger = CustomUser.builder().id(3L).username("stranger").build();
        ChefProfile profile = ChefProfile.builder().id(1L).user(chef).build();
        when(chefProfileRepository.findByUserId(chef.getId())).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.getChefProfile(stranger, chef.getId()))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void getAllChefProfiles_emptyTable_throwsProfileDoesNotExist() {
        when(chefProfileRepository.count()).thenReturn(0L);
        assertThatThrownBy(() -> service.getAllChefProfiles("rating_desc", 1))
                .isInstanceOf(ProfileDoesNotExistException.class);
    }

    @Test
    void getAllChefProfiles_nonEmpty_returnsPageEvenWhenRequestedPageIsPastTheEnd() {
        when(chefProfileRepository.count()).thenReturn(1L);
        Page<ChefProfile> emptyPage = new PageImpl<>(List.of());
        when(chefProfileRepository.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(emptyPage);

        ChefProfilePage page = service.getAllChefProfiles("rating_desc", 99);
        assertThat(page.count()).isEqualTo(1L);
        assertThat(page.items()).isEmpty();
    }

    @Test
    void getPopularChefs_empty_returnsEmptyListWithoutThrowing() {
        when(chefProfileRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        assertThat(service.getPopularChefs()).isEmpty();
    }
}
