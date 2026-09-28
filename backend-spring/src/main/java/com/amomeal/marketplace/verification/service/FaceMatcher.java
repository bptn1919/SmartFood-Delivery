package com.amomeal.marketplace.verification.service;

import java.util.Optional;

/**
 * Seam for Django's InsightFace ({@code buffalo_l}) face comparison of the CCCD portrait vs the
 * selfie (verification/services/face.py). There is no faithful Java equivalent of the InsightFace
 * ONNX pipeline in this stack, so the default {@link UnavailableFaceMatcher} returns empty - the
 * exact outcome Django produces when insightface fails to load or detects no face
 * ({@code compare_faces} returns None -> {@code FACE_NOT_DETECTED}, +50 risk, manual review).
 * A real matcher can be dropped in as a {@code @Primary} bean.
 */
public interface FaceMatcher {

    /** Cosine similarity in [0,1] rounded to 4 dp, or empty when a face could not be detected/compared. */
    Optional<Double> compare(byte[] cccdImage, byte[] selfieImage);
}
