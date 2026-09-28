package com.amomeal.marketplace.attachment.service;

import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Mirrors ../../backend/attachment/utils.py::Utils. */
final class AttachmentNaming {

    private AttachmentNaming() {
    }

    /** Mirrors Utils.generate_hashed_name: sha256(name + timestamp) + original extension. */
    static String generateHashedName(String fileName) {
        String name = fileName == null ? "" : fileName;
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        String extension = dot >= 0 ? name.substring(dot) : "";
        String toHash = base + System.currentTimeMillis() + System.nanoTime();
        return sha256Hex(toHash) + extension;
    }

    /** Mirrors Utils.get_content_type (mimetypes.guess_type -> best-effort MIME sniff by extension). */
    static String getContentType(String fileName) {
        if (fileName == null) {
            return null;
        }
        String guessed = URLConnection.guessContentTypeFromName(fileName);
        return guessed;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
