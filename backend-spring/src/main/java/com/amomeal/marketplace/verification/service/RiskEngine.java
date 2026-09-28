package com.amomeal.marketplace.verification.service;

import com.amomeal.marketplace.verification.entity.ChefVerificationSession;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.amomeal.marketplace.verification.gemini.GeminiVisionService.truthy;

/**
 * Port of ../../backend/verification/services/engine.py (RiskEngine + DecisionEngine). The AI may
 * only recommend rejection; the final approval belongs to an admin: score &lt; 80 -&gt;
 * PENDING_REVIEW, &gt;= 80 -&gt; REJECTED.
 */
public final class RiskEngine {

    public static final double FACE_PASS_THRESHOLD = 0.80;
    public static final double FACE_REVIEW_THRESHOLD = 0.65;

    private static final Map<String, Integer> WEIGHTS = Map.ofEntries(
            Map.entry("IMAGE_BLURRY_CCCD", 10),
            Map.entry("IMAGE_BLURRY_BUSINESS", 10),
            Map.entry("IMAGE_BLURRY_FOOD_SAFETY", 10),
            Map.entry("POSSIBLE_EDITING", 30),
            Map.entry("MISSING_SIGNATURE_BUSINESS", 10),
            Map.entry("MISSING_RED_STAMP_BUSINESS", 10),
            Map.entry("MISSING_SIGNATURE_FOOD_SAFETY", 10),
            Map.entry("MISSING_RED_STAMP_FOOD_SAFETY", 10),
            Map.entry("OWNER_NAME_MISMATCH", 50),
            Map.entry("ADDRESS_MISMATCH", 30),
            Map.entry("FOOD_SAFETY_CERT_EXPIRED", 100),
            Map.entry("FACE_MATCH_REVIEW", 20),
            Map.entry("FACE_MATCH_FAILED", 70),
            Map.entry("FACE_NOT_DETECTED", 50));

    private RiskEngine() {
    }

    private static Map<String, Object> orEmpty(Map<String, Object> m) {
        return m == null ? Map.of() : m;
    }

    /** {@code d.get(key, default)} then Python truthiness. */
    private static boolean flag(Map<String, Object> d, String key, boolean dflt) {
        return truthy(d.containsKey(key) ? d.get(key) : dflt);
    }

    public static List<String> collectRiskFlags(ChefVerificationSession session, LocalDate today) {
        List<String> flags = new ArrayList<>();

        Map<String, Object> cccd = orEmpty(session.getCccdConfirmed());
        if (!flag(cccd, "image_clear", true)) {
            flags.add("IMAGE_BLURRY_CCCD");
        }
        if (flag(cccd, "possible_editing", false)) {
            flags.add("POSSIBLE_EDITING");
        }

        Map<String, Object> business = orEmpty(session.getBusinessConfirmed());
        if (!flag(business, "image_clear", true)) {
            flags.add("IMAGE_BLURRY_BUSINESS");
        }
        if (!flag(business, "has_signature", true)) {
            flags.add("MISSING_SIGNATURE_BUSINESS");
        }
        if (!flag(business, "has_red_stamp", true)) {
            flags.add("MISSING_RED_STAMP_BUSINESS");
        }

        Map<String, Object> foodSafety = orEmpty(session.getFoodSafetyConfirmed());
        if (!flag(foodSafety, "image_clear", true)) {
            flags.add("IMAGE_BLURRY_FOOD_SAFETY");
        }
        if (!flag(foodSafety, "has_signature", true)) {
            flags.add("MISSING_SIGNATURE_FOOD_SAFETY");
        }
        if (!flag(foodSafety, "has_red_stamp", true)) {
            flags.add("MISSING_RED_STAMP_FOOD_SAFETY");
        }

        for (Map<String, Object> err : session.getCrossValidationErrors()) {
            Object c = err.get("code");
            String code = c == null ? "" : c.toString();
            if (code.contains("OWNER_NAME_MISMATCH")) {
                if (!flags.contains("OWNER_NAME_MISMATCH")) {
                    flags.add("OWNER_NAME_MISMATCH");
                }
            } else if (code.contains("ADDRESS_MISMATCH")) {
                if (!flags.contains("ADDRESS_MISMATCH")) {
                    flags.add("ADDRESS_MISMATCH");
                }
            } else if (code.equals("FOOD_SAFETY_CERT_EXPIRED")) {
                flags.add("FOOD_SAFETY_CERT_EXPIRED");
            }
        }

        Object expiry = foodSafety.get("expiry_date");
        if (truthy(expiry)) {
            try {
                if (LocalDate.parse(expiry.toString()).isBefore(today) && !flags.contains("FOOD_SAFETY_CERT_EXPIRED")) {
                    flags.add("FOOD_SAFETY_CERT_EXPIRED");
                }
            } catch (DateTimeParseException ignored) {
                // Django: (ValueError, TypeError) -> pass
            }
        }

        Double score = session.getFaceSimilarityScore();
        if (score == null) {
            flags.add("FACE_NOT_DETECTED");
        } else if (score < FACE_REVIEW_THRESHOLD) {
            flags.add("FACE_MATCH_FAILED");
        } else if (score < FACE_PASS_THRESHOLD) {
            flags.add("FACE_MATCH_REVIEW");
        }
        return flags;
    }

    public static int calculateRiskScore(List<String> flags) {
        return flags.stream().mapToInt(f -> WEIGHTS.getOrDefault(f, 0)).sum();
    }

    public static String makeDecision(int riskScore) {
        return riskScore >= 80 ? "REJECTED" : "PENDING_REVIEW";
    }
}
