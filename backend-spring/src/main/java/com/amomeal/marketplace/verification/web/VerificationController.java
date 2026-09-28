package com.amomeal.marketplace.verification.web;

import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.verification.dto.VerificationRequests.*;
import com.amomeal.marketplace.verification.dto.VerificationResponses.*;
import com.amomeal.marketplace.verification.entity.ChefVerificationSession;
import com.amomeal.marketplace.verification.service.VerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Mirrors ../../backend/verification/api.py - prefix {@code /api/verification}, every endpoint
 * CHEF-only ({@code @require_group(CHEF)} == {@code hasRole('CHEF')}).
 */
@RestController
@RequestMapping("/api/verification")
@PreAuthorize("hasRole('CHEF')")
@RequiredArgsConstructor
public class VerificationController {

    private final VerificationService service;

    @PostMapping("/cccd/analyze")
    public AnalyzeCccdResponse analyzeCccd(@AuthenticationPrincipal CustomUser user,
                                           @Valid @RequestBody AnalyzeCccdRequest payload) {
        return service.analyzeCccd(user.getId(), payload.attachmentUids());
    }

    @PostMapping("/cccd/confirm")
    public ConfirmResponse confirmCccd(@AuthenticationPrincipal CustomUser user) {
        service.confirmCccd(user.getId());
        return new ConfirmResponse("CONFIRMED");
    }

    @PostMapping("/business/analyze")
    public AnalyzeBusinessResponse analyzeBusiness(@AuthenticationPrincipal CustomUser user,
                                                   @Valid @RequestBody AnalyzeDocumentRequest payload) {
        return service.analyzeBusiness(user.getId(), payload.attachmentUids());
    }

    @PostMapping("/business/confirm")
    public ConfirmResponse confirmBusiness(@AuthenticationPrincipal CustomUser user) {
        service.confirmBusiness(user.getId());
        return new ConfirmResponse("CONFIRMED");
    }

    @PostMapping("/food-safety/analyze")
    public AnalyzeFoodSafetyResponse analyzeFoodSafety(@AuthenticationPrincipal CustomUser user,
                                                       @Valid @RequestBody AnalyzeDocumentRequest payload) {
        return service.analyzeFoodSafety(user.getId(), payload.attachmentUids());
    }

    @PostMapping("/food-safety/confirm")
    public ConfirmResponse confirmFoodSafety(@AuthenticationPrincipal CustomUser user) {
        service.confirmFoodSafety(user.getId());
        return new ConfirmResponse("CONFIRMED");
    }

    @PostMapping("/cross-validate")
    public CrossValidationResponse crossValidate(@AuthenticationPrincipal CustomUser user) {
        Map<String, Object> result = service.crossValidate(user.getId());
        return new CrossValidationResponse(Boolean.TRUE.equals(result.get("passed")), (String) result.get("next_step"));
    }

    @GetMapping("/selfie/code")
    public VerificationCodeResponse requestSelfieCode(@AuthenticationPrincipal CustomUser user) {
        return service.requestSelfieCode(user.getId());
    }

    @PostMapping("/selfie/analyze")
    public VerificationDecisionResponse analyzeSelfie(@AuthenticationPrincipal CustomUser user,
                                                      @Valid @RequestBody AnalyzeSelfieRequest payload) {
        ChefVerificationSession session = service.analyzeSelfie(user.getId(), payload.attachmentUid());
        return new VerificationDecisionResponse(session.getDecision(), session.getRiskScore(), session.getRiskFlags(),
                session.getFaceSimilarityScore(), session.getStatus().name());
    }

    @GetMapping("/status")
    public SessionStatusResponse status(@AuthenticationPrincipal CustomUser user) {
        return service.getStatus(user.getId());
    }
}
