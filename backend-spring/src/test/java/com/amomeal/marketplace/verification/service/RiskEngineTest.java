package com.amomeal.marketplace.verification.service;

import com.amomeal.marketplace.verification.entity.ChefVerificationSession;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 1);

    private ChefVerificationSession clean() {
        ChefVerificationSession s = new ChefVerificationSession();
        s.setCccdConfirmed(Map.of("image_clear", true, "possible_editing", false));
        s.setBusinessConfirmed(Map.of("image_clear", true, "has_signature", true, "has_red_stamp", true));
        s.setFoodSafetyConfirmed(Map.of("has_signature", true, "has_red_stamp", true, "expiry_date", "2099-01-01"));
        s.setFaceSimilarityScore(0.9);
        return s;
    }

    @Test
    void cleanSession_noFlags_pendingReview() {
        List<String> flags = RiskEngine.collectRiskFlags(clean(), TODAY);
        assertThat(flags).isEmpty();
        assertThat(RiskEngine.calculateRiskScore(flags)).isZero();
        assertThat(RiskEngine.makeDecision(0)).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void imageQualityAndDocumentFlags() {
        ChefVerificationSession s = clean();
        s.setCccdConfirmed(Map.of("image_clear", false, "possible_editing", true));
        s.setBusinessConfirmed(Map.of("image_clear", false, "has_signature", false, "has_red_stamp", false));
        s.setFoodSafetyConfirmed(Map.of("image_clear", false, "has_signature", false, "has_red_stamp", false));
        List<String> flags = RiskEngine.collectRiskFlags(s, TODAY);
        assertThat(flags).containsExactly("IMAGE_BLURRY_CCCD", "POSSIBLE_EDITING", "IMAGE_BLURRY_BUSINESS",
                "MISSING_SIGNATURE_BUSINESS", "MISSING_RED_STAMP_BUSINESS", "IMAGE_BLURRY_FOOD_SAFETY",
                "MISSING_SIGNATURE_FOOD_SAFETY", "MISSING_RED_STAMP_FOOD_SAFETY");
        assertThat(RiskEngine.calculateRiskScore(flags)).isEqualTo(10 + 30 + 10 + 10 + 10 + 10 + 10 + 10);
    }

    @Test
    void crossValidationErrorsCollapseToOneFlagEach() {
        ChefVerificationSession s = clean();
        s.setCrossValidationErrors(List.of(
                Map.of("code", "OWNER_NAME_MISMATCH_CCCD_BUSINESS"),
                Map.of("code", "OWNER_NAME_MISMATCH_BUSINESS_FOOD_SAFETY"),
                Map.of("code", "ADDRESS_MISMATCH_BUSINESS_FOOD_SAFETY"),
                Map.of("code", "FOOD_SAFETY_CERT_EXPIRED")));
        List<String> flags = RiskEngine.collectRiskFlags(s, TODAY);
        assertThat(flags).containsExactly("OWNER_NAME_MISMATCH", "ADDRESS_MISMATCH", "FOOD_SAFETY_CERT_EXPIRED");
        assertThat(RiskEngine.calculateRiskScore(flags)).isEqualTo(50 + 30 + 100);
        assertThat(RiskEngine.makeDecision(180)).isEqualTo("REJECTED");
    }

    @Test
    void expiredCertDoubleCheckedFromConfirmedData_withoutDuplicate() {
        ChefVerificationSession s = clean();
        s.setFoodSafetyConfirmed(Map.of("expiry_date", "2020-01-01"));
        assertThat(RiskEngine.collectRiskFlags(s, TODAY)).containsExactly("FOOD_SAFETY_CERT_EXPIRED");

        s.setCrossValidationErrors(List.of(Map.of("code", "FOOD_SAFETY_CERT_EXPIRED")));
        assertThat(RiskEngine.collectRiskFlags(s, TODAY)).containsExactly("FOOD_SAFETY_CERT_EXPIRED");

        s.setCrossValidationErrors(List.of());
        s.setFoodSafetyConfirmed(Map.of("expiry_date", "garbage"));
        assertThat(RiskEngine.collectRiskFlags(s, TODAY)).isEmpty();
    }

    @Test
    void faceThresholds() {
        ChefVerificationSession s = clean();
        s.setFaceSimilarityScore(null);
        assertThat(RiskEngine.collectRiskFlags(s, TODAY)).containsExactly("FACE_NOT_DETECTED");
        s.setFaceSimilarityScore(0.64);
        assertThat(RiskEngine.collectRiskFlags(s, TODAY)).containsExactly("FACE_MATCH_FAILED");
        s.setFaceSimilarityScore(0.65);
        assertThat(RiskEngine.collectRiskFlags(s, TODAY)).containsExactly("FACE_MATCH_REVIEW");
        s.setFaceSimilarityScore(0.79);
        assertThat(RiskEngine.collectRiskFlags(s, TODAY)).containsExactly("FACE_MATCH_REVIEW");
        s.setFaceSimilarityScore(0.80);
        assertThat(RiskEngine.collectRiskFlags(s, TODAY)).isEmpty();
    }

    @Test
    void decisionBoundary_and_unknownFlagWeighsZero() {
        assertThat(RiskEngine.makeDecision(79)).isEqualTo("PENDING_REVIEW");
        assertThat(RiskEngine.makeDecision(80)).isEqualTo("REJECTED");
        assertThat(RiskEngine.calculateRiskScore(List.of("FACE_MATCH_FAILED", "NOT_A_FLAG"))).isEqualTo(70);
    }

    @Test
    void missingConfirmedData_defaultsToNoQualityFlags() {
        ChefVerificationSession s = new ChefVerificationSession();
        s.setFaceSimilarityScore(0.95);
        assertThat(RiskEngine.collectRiskFlags(s, TODAY)).isEmpty();
    }
}
