package com.amomeal.marketplace.recommendation.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Shared by every full-stack recommendation test (same config + same properties = one cached
 * Spring context / one set of containers): replaces the real Gemini REST client with
 * {@link FakeGeminiClient}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RecommendationTestConfig {

    @Bean
    @Primary
    FakeGeminiClient fakeGeminiClient() {
        return new FakeGeminiClient();
    }
}
