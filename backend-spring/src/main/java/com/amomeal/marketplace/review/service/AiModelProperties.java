package com.amomeal.marketplace.review.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mirrors ../../backend/marketplace/settings.py:
 * {@code AI_MODEL_BASE_URL = os.getenv("AI_MODEL_BASE_URL", "http://localhost:8001")}
 * {@code AI_MODEL_TIMEOUT_SECONDS = int(os.getenv("AI_MODEL_TIMEOUT_SECONDS", "10"))}
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.ai-model")
public class AiModelProperties {

    private String baseUrl = "http://localhost:8001";
    private int timeoutSeconds = 10;
}
