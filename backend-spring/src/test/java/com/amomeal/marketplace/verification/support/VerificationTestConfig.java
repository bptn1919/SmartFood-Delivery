package com.amomeal.marketplace.verification.support;

import com.amomeal.marketplace.verification.service.FaceMatcher;
import com.amomeal.marketplace.verification.service.S3ObjectDeleter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Fakes for the verification module's external seams (never call Gemini / S3 / face engine). */
@TestConfiguration(proxyBeanMethods = false)
public class VerificationTestConfig {

    /** Face matcher returning a configurable score (empty = face not detected). */
    public static class FakeFaceMatcher implements FaceMatcher {
        public volatile Optional<Double> score = Optional.empty();

        @Override
        public Optional<Double> compare(byte[] cccdImage, byte[] selfieImage) {
            return score;
        }
    }

    /** Recording S3 deleter, enabled (== USE_S3 true). */
    public static class RecordingS3ObjectDeleter implements S3ObjectDeleter {
        public final List<String> deleted = new ArrayList<>();
        public volatile boolean enabled = true;

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public synchronized void delete(String bucket, String key) {
            deleted.add(bucket + "/" + key);
        }
    }

    @Bean
    @Primary
    FakeGeminiVisionClient fakeGeminiVisionClient() {
        return new FakeGeminiVisionClient();
    }

    @Bean
    @Primary
    FakeImageFetcher fakeImageFetcher() {
        return new FakeImageFetcher();
    }

    @Bean
    @Primary
    FakeFaceMatcher fakeFaceMatcher() {
        return new FakeFaceMatcher();
    }

    @Bean
    @Primary
    RecordingS3ObjectDeleter recordingS3ObjectDeleter() {
        return new RecordingS3ObjectDeleter();
    }
}
