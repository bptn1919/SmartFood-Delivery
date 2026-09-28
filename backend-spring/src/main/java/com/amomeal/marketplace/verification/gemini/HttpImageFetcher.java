package com.amomeal.marketplace.verification.gemini;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** JDK-HttpClient {@link ImageFetcher}, 15 s timeout, MIME from Content-Type (default image/jpeg). */
@Component
public class HttpImageFetcher implements ImageFetcher {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public FetchedImage fetch(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " fetching " + url);
            }
            String mime = response.headers().firstValue("Content-Type").orElse("image/jpeg");
            int semi = mime.indexOf(';');
            if (semi >= 0) {
                mime = mime.substring(0, semi);
            }
            return new FetchedImage(response.body(), mime.strip());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot fetch " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted fetching " + url, e);
        }
    }
}
