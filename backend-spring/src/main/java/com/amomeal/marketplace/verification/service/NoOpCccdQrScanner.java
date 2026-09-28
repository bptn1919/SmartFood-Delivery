package com.amomeal.marketplace.verification.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** Default {@link CccdQrScanner}: no QR decoding available. */
@Component
public class NoOpCccdQrScanner implements CccdQrScanner {
    @Override
    public Optional<Map<String, Object>> scan(byte[] backImage) {
        return Optional.empty();
    }
}
