package com.amomeal.marketplace.certificate.web;

import com.amomeal.marketplace.certificate.dto.CertificateAttachmentReorderRequest;
import com.amomeal.marketplace.certificate.dto.CertificateResponse;
import com.amomeal.marketplace.certificate.dto.SetCertificateStatusRequest;
import com.amomeal.marketplace.certificate.service.CertificateService;
import com.amomeal.marketplace.ingredient.dto.PageResponse;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/certificate/api.py (paths cross-checked with FE-admin certificateService.js /
 * constants.js API_ENDPOINTS.CERTIFICATES). FE-admin also references create/update/add-attachment/
 * remove-attachment routes, but Django removed them (certificates come from the verification flow) so
 * they 404 there and are not ported. Admin gating is inside CertificateService (403, as in Django).
 */
@RestController
@RequestMapping("/api/certificates")
@RequiredArgsConstructor
public class CertificateController {

    private final CertificateService certificateService;

    @GetMapping({"", "/"})
    public PageResponse<CertificateResponse> getMyCertificates(
            @AuthenticationPrincipal CustomUser user,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String categories,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return certificateService.getMyCertificates(user, search, status, categories, page, pageSize);
    }

    @GetMapping("/all")
    public PageResponse<CertificateResponse> getAllCertificates(
            @AuthenticationPrincipal CustomUser user,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String categories,
            @RequestParam(name = "chef_id", required = false) Long chefId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return certificateService.getAllCertificates(user, search, status, categories, chefId, page, pageSize);
    }

    @GetMapping("/{uid}")
    public CertificateResponse getCertificate(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        return certificateService.getCertificate(user, uid);
    }

    @PatchMapping("/{uid}/status")
    public CertificateResponse setStatus(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
                                         @Valid @RequestBody SetCertificateStatusRequest payload) {
        return certificateService.setStatus(user, uid, payload);
    }

    @PatchMapping("/{uid}/deleted")
    public boolean softDelete(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        return certificateService.softDelete(user, uid);
    }

    @PatchMapping("/{uid}/restored")
    public boolean restore(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        return certificateService.restore(user, uid);
    }

    @PatchMapping("/{uid}/attachments/reorder")
    public CertificateResponse reorderAttachments(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
                                                  @Valid @RequestBody List<CertificateAttachmentReorderRequest> payload) {
        return certificateService.reorderAttachments(user, uid, payload);
    }
}
