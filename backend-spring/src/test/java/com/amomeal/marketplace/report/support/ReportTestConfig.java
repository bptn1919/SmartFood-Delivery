package com.amomeal.marketplace.report.support;

import com.amomeal.marketplace.recommendation.support.FakeGeminiClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the real Gemini REST client with a fake for every full-stack report test (never call real Gemini). */
@TestConfiguration(proxyBeanMethods = false)
public class ReportTestConfig {

    @Bean
    @Primary
    FakeGeminiClient fakeGeminiClient() {
        return new FakeGeminiClient();
    }
}
