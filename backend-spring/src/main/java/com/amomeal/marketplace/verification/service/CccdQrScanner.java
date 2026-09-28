package com.amomeal.marketplace.verification.service;

import java.util.Map;
import java.util.Optional;

/**
 * Seam for Django's back-of-CCCD QR scan (verification/services/qr.py, OpenCV/pyzbar). Returns the
 * parsed QR fields ({@code cccd_number}, {@code gender}, {@code old_cmnd}, ...) or empty when no QR
 * was found/decodable. The default {@link NoOpCccdQrScanner} finds nothing, which in Django yields
 * {@code qr_verified=False} (the same as a failed scan) - no mismatch can be raised.
 */
public interface CccdQrScanner {
    Optional<Map<String, Object>> scan(byte[] backImage);
}
