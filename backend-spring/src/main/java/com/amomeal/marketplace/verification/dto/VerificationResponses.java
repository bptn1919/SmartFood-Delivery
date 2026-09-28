package com.amomeal.marketplace.verification.dto;

import java.util.List;
import java.util.Map;

/** Mirrors verification/schemas/responses.py (snake_case comes from the global Jackson strategy). */
public final class VerificationResponses {

    private VerificationResponses() {
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : v.toString();
    }

    public record CccdExtracted(String fullName, String cccdNumber, String dateOfBirth, String address) {
        public static CccdExtracted of(Map<String, Object> m) {
            return new CccdExtracted(str(m, "full_name"), str(m, "cccd_number"), str(m, "date_of_birth"), str(m, "address"));
        }
    }

    public record BusinessExtracted(String ownerName, String businessName, String businessLicenseNumber,
                                    String address, String issueDate) {
        public static BusinessExtracted of(Map<String, Object> m) {
            return new BusinessExtracted(str(m, "owner_name"), str(m, "business_name"),
                    str(m, "business_license_number"), str(m, "address"), str(m, "issue_date"));
        }
    }

    public record FoodSafetyExtracted(String ownerName, String facilityName, String certificateNumber,
                                      String address, String issueDate, String expiryDate) {
        public static FoodSafetyExtracted of(Map<String, Object> m) {
            return new FoodSafetyExtracted(str(m, "owner_name"), str(m, "facility_name"), str(m, "certificate_number"),
                    str(m, "address"), str(m, "issue_date"), str(m, "expiry_date"));
        }
    }

    public record AnalyzeCccdResponse(long sessionId, CccdExtracted extracted) {
    }

    public record AnalyzeBusinessResponse(long sessionId, BusinessExtracted extracted) {
    }

    public record AnalyzeFoodSafetyResponse(long sessionId, FoodSafetyExtracted extracted) {
    }

    public record ConfirmResponse(String status) {
    }

    public record CrossValidationResponse(boolean passed, String nextStep) {
    }

    public record VerificationCodeResponse(String verificationCode, String expiresAt) {
    }

    public record VerificationDecisionResponse(String decision, int riskScore, List<String> riskFlags,
                                               Double faceSimilarityScore, String status) {
    }

    public record VerifiedIdentity(String fullName, String dateOfBirth) {
    }

    public record SessionStatusResponse(String status, String cccdStatus, String businessStatus,
                                        String foodSafetyStatus, Boolean crossValidationPassed, String decision,
                                        int riskScore, List<String> riskFlags, Double faceSimilarityScore,
                                        String cccdNumberMasked, VerifiedIdentity verifiedIdentity,
                                        String verifiedAt, String selfieUrl) {
    }
}
