package com.amomeal.marketplace.verification.service;

import org.springframework.stereotype.Component;

import java.util.Optional;

/** Default {@link FaceMatcher}: no face engine available -> similarity unknown (Django: None). */
@Component
public class UnavailableFaceMatcher implements FaceMatcher {
    @Override
    public Optional<Double> compare(byte[] cccdImage, byte[] selfieImage) {
        return Optional.empty();
    }
}
