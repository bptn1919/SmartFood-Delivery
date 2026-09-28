package com.amomeal.marketplace.verification.gemini;

/**
 * Downloads an attachment's bytes from its public URL, exactly like Django's
 * {@code requests.get(public_url, timeout=15)} in gemini.py/verification.py. (The attachment
 * module's {@code AttachmentStorageService} only writes/checks/URL-builds - it has no read - and
 * Django itself never reads through storage here, so the public URL is used.) Tests substitute a fake.
 */
public interface ImageFetcher {

    record FetchedImage(byte[] data, String mimeType) {
    }

    /** @throws RuntimeException on a non-2xx status or transport failure (Django raise_for_status). */
    FetchedImage fetch(String url);
}
