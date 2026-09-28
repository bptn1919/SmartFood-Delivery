package com.amomeal.marketplace.certificate.service;

import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.attachment.service.AttachmentService;
import com.amomeal.marketplace.certificate.dto.CertificateRequest;
import com.amomeal.marketplace.certificate.dto.SetCertificateStatusRequest;
import com.amomeal.marketplace.certificate.entity.Certificate;
import com.amomeal.marketplace.certificate.entity.CertificateStatus;
import com.amomeal.marketplace.certificate.entity.CertificateType;
import com.amomeal.marketplace.certificate.exception.CertificateNotFoundException;
import com.amomeal.marketplace.certificate.exception.CertificatePermissionDeniedException;
import com.amomeal.marketplace.certificate.repository.CertificateAttachmentRepository;
import com.amomeal.marketplace.certificate.repository.CertificateRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests (Mockito) for {@link CertificateService}: lifecycle, ownership, admin gating. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertificateServiceTest {

    @Mock private CertificateRepository certificateRepository;
    @Mock private CertificateAttachmentRepository certificateAttachmentRepository;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private CustomUserRepository customUserRepository;
    @Mock private CertificateReviewHook reviewHook;

    private CertificateService service;
    private CustomUser chef;
    private CustomUser otherChef;
    private CustomUser admin;
    private Certificate cert;

    private static CustomUser user(long id, UserRole role) {
        CustomUser u = CustomUser.builder().id(id).username("u" + id).build();
        u.addRole(role);
        return u;
    }

    @BeforeEach
    void setUp() {
        service = new CertificateService(certificateRepository, certificateAttachmentRepository,
                attachmentRepository, attachmentService, customUserRepository, reviewHook);
        chef = user(1L, UserRole.CHEF);
        otherChef = user(2L, UserRole.CHEF);
        admin = user(3L, UserRole.ADMIN);
        cert = Certificate.builder().uid(UUID.randomUUID()).name("Giấy ATTP").issuedBy("Sở Y tế")
                .issueDate(LocalDate.of(2026, 1, 1)).certificateType(CertificateType.FOOD_SAFETY).owner(chef).build();
        when(certificateRepository.findByUidAndDeletedFalse(cert.getUid())).thenReturn(Optional.of(cert));
        when(certificateRepository.findById(cert.getUid())).thenReturn(Optional.of(cert));
        when(certificateRepository.save(any(Certificate.class))).thenAnswer(i -> i.getArgument(0));
        when(customUserRepository.getReferenceById(admin.getId())).thenReturn(admin);
        when(certificateAttachmentRepository.findByCertificateOrderByPositionAsc(any())).thenReturn(List.of());
    }

    @Test
    void submit_createsPendingCertificateOwnedByCaller() {
        when(customUserRepository.getReferenceById(chef.getId())).thenReturn(chef);
        var resp = service.createCertificate(chef, new CertificateRequest("Giấy ATTP", null, "Sở Y tế",
                LocalDate.of(2026, 1, 1), null, CertificateType.FOOD_SAFETY));
        assertThat(resp.status()).isEqualTo(CertificateStatus.PENDING);
        assertThat(resp.owner()).isEqualTo(1L);
    }

    @Test
    void approve_setsActiveVerifierAndTimestamp_andFiresReviewHook() {
        var resp = service.setStatus(admin, cert.getUid(), new SetCertificateStatusRequest(CertificateStatus.ACTIVE, null));
        assertThat(resp.status()).isEqualTo(CertificateStatus.ACTIVE);
        assertThat(resp.verifiedBy()).isEqualTo(3L);
        assertThat(resp.verifiedAt()).isNotNull();
        verify(reviewHook).afterReviewed(cert.getUid());
    }

    @Test
    void reject_requiresReason_thenStoresIt() {
        assertThatThrownBy(() -> service.setStatus(admin, cert.getUid(),
                new SetCertificateStatusRequest(CertificateStatus.REVOKED, null)))
                .isInstanceOf(CertificatePermissionDeniedException.class)
                .hasMessage("Vui lòng cung cấp lý do từ chối khi REVOKE certificate.");
        assertThatThrownBy(() -> service.setStatus(admin, cert.getUid(),
                new SetCertificateStatusRequest(CertificateStatus.REVOKED, "")))
                .isInstanceOf(CertificatePermissionDeniedException.class);
        verify(certificateRepository, never()).save(any());

        var resp = service.setStatus(admin, cert.getUid(),
                new SetCertificateStatusRequest(CertificateStatus.REVOKED, "Ảnh mờ"));
        assertThat(resp.status()).isEqualTo(CertificateStatus.REVOKED);
        assertThat(resp.rejectionReason()).isEqualTo("Ảnh mờ");
    }

    @Test
    void approveAfterReject_keepsOldRejectionReason() {
        cert.setRejectionReason("cũ");
        service.setStatus(admin, cert.getUid(), new SetCertificateStatusRequest(CertificateStatus.ACTIVE, null));
        assertThat(cert.getRejectionReason()).isEqualTo("cũ"); // Django never clears it
    }

    @Test
    void setStatus_nonAdmin_isForbidden_andUnknownCertIs404() {
        assertThatThrownBy(() -> service.setStatus(chef, cert.getUid(),
                new SetCertificateStatusRequest(CertificateStatus.ACTIVE, null)))
                .isInstanceOf(CertificatePermissionDeniedException.class)
                .hasMessage("Only admin can access this endpoint");
        assertThatThrownBy(() -> service.setStatus(admin, UUID.randomUUID(),
                new SetCertificateStatusRequest(CertificateStatus.ACTIVE, null)))
                .isInstanceOf(CertificateNotFoundException.class);
    }

    @Test
    void reviewHookFailure_isSwallowed() {
        doThrow(new RuntimeException("boom")).when(reviewHook).afterReviewed(cert.getUid());
        var resp = service.setStatus(admin, cert.getUid(), new SetCertificateStatusRequest(CertificateStatus.ACTIVE, null));
        assertThat(resp.status()).isEqualTo(CertificateStatus.ACTIVE);
    }

    @Test
    void getCertificate_ownerAndAdminAllowed_otherChefForbidden() {
        assertThat(service.getCertificate(chef, cert.getUid()).uid()).isEqualTo(cert.getUid());
        assertThat(service.getCertificate(admin, cert.getUid()).uid()).isEqualTo(cert.getUid());
        assertThatThrownBy(() -> service.getCertificate(otherChef, cert.getUid()))
                .isInstanceOf(CertificatePermissionDeniedException.class);
    }

    @Test
    void getCertificate_nullOwner_skipsOwnershipCheck_likeDjango() {
        cert.setOwner(null);
        assertThat(service.getCertificate(otherChef, cert.getUid())).isNotNull();
    }

    @Test
    void softDelete_onlyPending_onlyAdmin() {
        assertThatThrownBy(() -> service.softDelete(chef, cert.getUid()))
                .isInstanceOf(CertificatePermissionDeniedException.class);
        cert.setStatus(CertificateStatus.ACTIVE);
        assertThatThrownBy(() -> service.softDelete(admin, cert.getUid()))
                .isInstanceOf(CertificatePermissionDeniedException.class)
                .hasMessage("Chỉ có thể xóa certificate khi đang ở trạng thái PENDING.");
        cert.setStatus(CertificateStatus.PENDING);
        assertThat(service.softDelete(admin, cert.getUid())).isTrue();
        assertThat(cert.isDeleted()).isTrue();
    }

    @Test
    void restore_returnsFalseWhenNotDeleted_trueWhenDeleted_404WhenMissing() {
        assertThat(service.restore(admin, cert.getUid())).isFalse();
        cert.setDeleted(true);
        assertThat(service.restore(admin, cert.getUid())).isTrue();
        assertThat(cert.isDeleted()).isFalse();
        assertThatThrownBy(() -> service.restore(admin, UUID.randomUUID()))
                .isInstanceOf(CertificateNotFoundException.class);
        assertThatThrownBy(() -> service.restore(chef, cert.getUid()))
                .isInstanceOf(CertificatePermissionDeniedException.class);
    }

    @Test
    void update_onlyWhilePending() {
        var req = new CertificateRequest("Mới", null, "X", LocalDate.of(2026, 2, 2), null, CertificateType.BUSINESS_LICENSE);
        assertThat(service.updateCertificate(cert.getUid(), req).name()).isEqualTo("Mới");
        cert.setStatus(CertificateStatus.ACTIVE);
        assertThatThrownBy(() -> service.updateCertificate(cert.getUid(), req))
                .isInstanceOf(CertificatePermissionDeniedException.class);
    }
}
