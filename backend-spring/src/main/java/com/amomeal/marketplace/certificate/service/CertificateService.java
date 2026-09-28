package com.amomeal.marketplace.certificate.service;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.exception.AttachmentNotFoundException;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.attachment.service.AttachmentService;
import com.amomeal.marketplace.certificate.dto.CertificateAttachmentReorderRequest;
import com.amomeal.marketplace.certificate.dto.CertificateRequest;
import com.amomeal.marketplace.certificate.dto.CertificateResponse;
import com.amomeal.marketplace.certificate.dto.SetCertificateStatusRequest;
import com.amomeal.marketplace.certificate.entity.Certificate;
import com.amomeal.marketplace.certificate.entity.CertificateAttachment;
import com.amomeal.marketplace.certificate.entity.CertificateStatus;
import com.amomeal.marketplace.certificate.entity.CertificateType;
import com.amomeal.marketplace.certificate.exception.CertificateNotFoundException;
import com.amomeal.marketplace.certificate.exception.CertificatePermissionDeniedException;
import com.amomeal.marketplace.certificate.repository.CertificateAttachmentRepository;
import com.amomeal.marketplace.certificate.repository.CertificateRepository;
import com.amomeal.marketplace.ingredient.dto.PageResponse;
import com.amomeal.marketplace.ingredient.service.RemoveAccents;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Mirrors ../../backend/certificate/{services,orm}. Authorization is done here (throwing 403
 * PERMISSION_DENIED with Django's messages) rather than via @PreAuthorize, because Django's
 * require_admin / require_owner_or_admin raise PermissionDeniedError (403), not a 401.
 *
 * <p>Only the routes Django actually mounts are exposed over HTTP (list mine, list all, detail,
 * status, deleted, restored, attachments/reorder). create/update/addAttachments/removeAttachment exist
 * in Django's service but have no route (certificates come from the verification flow); they are ported
 * as service methods for the future verification module.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateService {

    private final CertificateRepository certificateRepository;
    private final CertificateAttachmentRepository certificateAttachmentRepository;
    private final AttachmentRepository attachmentRepository;
    private final AttachmentService attachmentService;
    private final CustomUserRepository customUserRepository;
    private final CertificateReviewHook reviewHook;

    // ------------------------------------------------------------------ helpers

    private static void requireAdmin(CustomUser user) {
        if (!user.isAdmin()) {
            throw new CertificatePermissionDeniedException("Only admin can access this endpoint");
        }
    }

    private CertificateResponse toResponse(Certificate certificate) {
        return CertificateResponse.of(certificate,
                certificateAttachmentRepository.findByCertificateOrderByPositionAsc(certificate));
    }

    private Certificate getByUid(UUID uid) {
        return certificateRepository.findByUidAndDeletedFalse(uid).orElseThrow(CertificateNotFoundException::new);
    }

    /** Django BaseCertificateFilterSchema (+ chef_id for the admin variant). Bogus enum values match nothing. */
    private static Specification<Certificate> filter(Long ownerId, Long chefId, String search, String status,
                                                     String categories) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.isFalse(root.get("deleted")));
            if (ownerId != null) {
                ps.add(cb.equal(root.get("owner").get("id"), ownerId));
            }
            if (chefId != null) {
                ps.add(cb.equal(root.get("owner").get("id"), chefId));
            }
            if (search != null) {
                ps.add(cb.like(root.get("nameNoAccent"), "%" + RemoveAccents.apply(search) + "%"));
            }
            if (status != null && !"all".equals(status)) {
                CertificateStatus parsed = parseStatus(status);
                ps.add(parsed == null ? cb.disjunction() : cb.equal(root.get("status"), parsed));
            }
            if (categories != null) {
                List<CertificateType> types = new ArrayList<>();
                for (String c : categories.split(",")) {
                    try {
                        types.add(CertificateType.valueOf(c));
                    } catch (IllegalArgumentException ignored) {
                        // Django: an unknown category simply never matches
                    }
                }
                ps.add(types.isEmpty() ? cb.disjunction() : root.get("certificateType").in(types));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private static CertificateStatus parseStatus(String s) {
        try {
            return CertificateStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private PageResponse<CertificateResponse> page(Specification<Certificate> spec, int page, int pageSize) {
        int size = Math.max(1, pageSize);
        int number = Math.max(1, page);
        // PORT-NOTE: Django's queryset is unordered; newest-first here for a deterministic page order.
        Page<Certificate> result = certificateRepository.findAll(spec,
                PageRequest.of(number - 1, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(result.getContent().stream().map(this::toResponse).toList(), number, size,
                result.getTotalElements());
    }

    // ------------------------------------------------------------------ reads

    /** GET /api/certificates/ - the caller's own certificates (auth only, no role gate in Django). */
    @Transactional(readOnly = true)
    public PageResponse<CertificateResponse> getMyCertificates(CustomUser user, String search, String status,
                                                               String categories, int page, int pageSize) {
        return page(filter(user.getId(), null, search, status, categories), page, pageSize);
    }

    /** GET /api/certificates/all - ADMIN. */
    @Transactional(readOnly = true)
    public PageResponse<CertificateResponse> getAllCertificates(CustomUser user, String search, String status,
                                                                String categories, Long chefId, int page,
                                                                int pageSize) {
        requireAdmin(user);
        return page(filter(null, chefId, search, status, categories), page, pageSize);
    }

    /**
     * GET /api/certificates/{uid} - Django's require_owner_or_admin: 404 first, then owner-or-ADMIN.
     * PORT-NOTE: a certificate whose owner is NULL (owner deleted, SET_NULL) skips the ownership check
     * entirely in Django ("if owner is not None") - readable by any authenticated user. Preserved.
     */
    @Transactional(readOnly = true)
    public CertificateResponse getCertificate(CustomUser user, UUID uid) {
        Certificate certificate = getByUid(uid);
        if (certificate.getOwner() != null && !certificate.getOwner().getId().equals(user.getId())
                && !user.isAdmin()) {
            throw new CertificatePermissionDeniedException("Bạn không có quyền truy cập certificate này.");
        }
        return toResponse(certificate);
    }

    // ------------------------------------------------------------------ admin review

    /**
     * PATCH /api/certificates/{uid}/status - ADMIN. Any target status is accepted (no transition table in
     * Django); REVOKED requires a reason; rejection_reason is only overwritten when non-empty (never cleared).
     */
    @Transactional
    public CertificateResponse setStatus(CustomUser admin, UUID uid, SetCertificateStatusRequest request) {
        requireAdmin(admin);
        String reason = request.rejectionReason();
        if (request.status() == CertificateStatus.REVOKED && (reason == null || reason.isEmpty())) {
            throw new CertificatePermissionDeniedException(
                    "Vui lòng cung cấp lý do từ chối khi REVOKE certificate.");
        }
        Certificate certificate = getByUid(uid);
        certificate.setStatus(request.status());
        certificate.setVerifiedBy(customUserRepository.getReferenceById(admin.getId()));
        certificate.setVerifiedAt(Instant.now());
        if (reason != null && !reason.isEmpty()) {
            certificate.setRejectionReason(reason);
        }
        certificate = certificateRepository.save(certificate);
        try {
            reviewHook.afterReviewed(uid);
        } catch (RuntimeException e) {
            // Django's _cleanup_selfie_if_fully_reviewed swallows and logs everything
            log.error("_cleanup_selfie_if_fully_reviewed failed: {}", e.getMessage());
        }
        return toResponse(certificate);
    }

    /** PATCH /api/certificates/{uid}/deleted - ADMIN; only PENDING certificates may be hidden. */
    @Transactional
    public boolean softDelete(CustomUser admin, UUID uid) {
        requireAdmin(admin);
        Certificate certificate = getByUid(uid);
        if (certificate.getStatus() != CertificateStatus.PENDING) {
            throw new CertificatePermissionDeniedException(
                    "Chỉ có thể xóa certificate khi đang ở trạng thái PENDING.");
        }
        certificate.setDeleted(true);
        certificateRepository.save(certificate);
        return true;
    }

    /**
     * PATCH /api/certificates/{uid}/restored - ADMIN. Looks up WITHOUT the deleted filter; returns false
     * (still 200 with data=false) when the certificate was not deleted.
     */
    @Transactional
    public boolean restore(CustomUser user, UUID uid) {
        requireAdmin(user);
        Certificate certificate = certificateRepository.findById(uid).orElseThrow(CertificateNotFoundException::new);
        boolean isOwner = certificate.getOwner() != null && certificate.getOwner().getId().equals(user.getId());
        if (!(isOwner || user.isAdmin())) {
            throw new CertificatePermissionDeniedException("Bạn không có quyền khôi phục certificate này.");
        }
        if (!certificate.isDeleted()) {
            return false;
        }
        certificate.setDeleted(false);
        certificateRepository.save(certificate);
        return true;
    }

    /**
     * PATCH /api/certificates/{uid}/attachments/reorder - ADMIN. PORT-NOTE: Django validates that each
     * listed attachment belongs to the certificate, then DELETES ALL links and re-creates only the listed
     * ones with position = list index (the request's own position field is ignored) - unlisted attachments
     * are dropped. Preserved.
     */
    @Transactional
    public CertificateResponse reorderAttachments(CustomUser admin, UUID uid,
                                                  List<CertificateAttachmentReorderRequest> payload) {
        requireAdmin(admin);
        Certificate certificate = getByUid(uid);
        for (CertificateAttachmentReorderRequest item : payload) {
            if (!certificateAttachmentRepository.existsByCertificateAndAttachmentUid(certificate,
                    item.attachmentUid())) {
                throw new AttachmentNotFoundException(item.attachmentUid() + " not found");
            }
        }
        certificateAttachmentRepository.deleteAllByCertificate(certificate);
        int idx = 0;
        for (CertificateAttachmentReorderRequest item : payload) {
            certificateAttachmentRepository.save(CertificateAttachment.builder()
                    .certificate(certificate)
                    .attachment(attachmentRepository.getReferenceById(item.attachmentUid()))
                    .position(idx++).build());
        }
        certificateAttachmentRepository.flush();
        return toResponse(certificate);
    }

    // ------------------------------------------------------------------ service-only (no Django route)

    /** Django create_certificate: PENDING certificate owned by {@code owner}. */
    @Transactional
    public CertificateResponse createCertificate(CustomUser owner, CertificateRequest request) {
        Certificate saved = certificateRepository.save(Certificate.builder()
                .name(request.name()).description(request.description()).issuedBy(request.issuedBy())
                .issueDate(request.issueDate()).expirationDate(request.expirationDate())
                .certificateType(request.certificateType())
                .owner(customUserRepository.getReferenceById(owner.getId())).build());
        return toResponse(saved);
    }

    /** Django update_certificate: only while PENDING. */
    @Transactional
    public CertificateResponse updateCertificate(UUID uid, CertificateRequest request) {
        Certificate certificate = getByUid(uid);
        if (certificate.getStatus() != CertificateStatus.PENDING) {
            throw new CertificatePermissionDeniedException(
                    "Chỉ có thể chỉnh sửa certificate khi đang ở trạng thái PENDING.");
        }
        certificate.setName(request.name());
        certificate.setDescription(request.description());
        certificate.setIssuedBy(request.issuedBy());
        certificate.setIssueDate(request.issueDate());
        certificate.setExpirationDate(request.expirationDate());
        certificate.setCertificateType(request.certificateType());
        return toResponse(certificateRepository.save(certificate));
    }

    /**
     * Django add_certificate_attachments: each attachment must be an uploaded+completed one
     * (AttachmentService.handleAttachment) and not already linked; new links go to the end
     * (position = max + 1, starting at 1).
     */
    @Transactional
    public CertificateResponse addAttachments(UUID uid, List<UUID> attachmentUids) {
        Certificate certificate = getByUid(uid);
        List<Attachment> attachments = new ArrayList<>();
        for (UUID attachmentUid : attachmentUids) {
            attachmentService.handleAttachment(attachmentUid);
            if (certificateAttachmentRepository.existsByCertificateAndAttachmentUid(certificate, attachmentUid)) {
                // Django: bare Exception -> 500
                throw new IllegalStateException("Attachment " + attachmentUid + " đã tồn tại trong certificate này");
            }
            attachments.add(attachmentRepository.findById(attachmentUid).orElseThrow(
                    () -> new AttachmentNotFoundException("Attachment " + attachmentUid + " không tồn tại")));
        }
        for (Attachment attachment : attachments) {
            Integer max = certificateAttachmentRepository.findMaxPosition(certificate);
            certificateAttachmentRepository.saveAndFlush(CertificateAttachment.builder()
                    .certificate(certificate).attachment(attachment)
                    .position((max == null ? 0 : max) + 1).build());
        }
        return toResponse(certificate);
    }

    /** Django remove_certificate_attachment. */
    @Transactional
    public CertificateResponse removeAttachment(UUID uid, UUID attachmentUid) {
        Certificate certificate = getByUid(uid);
        CertificateAttachment link = certificateAttachmentRepository
                .findByCertificateAndAttachmentUid(certificate, attachmentUid)
                .orElseThrow(() -> new AttachmentNotFoundException(
                        "Attachment " + attachmentUid + " không tồn tại trong certificate này"));
        certificateAttachmentRepository.delete(link);
        certificateAttachmentRepository.flush();
        return toResponse(certificate);
    }
}
