package com.amomeal.marketplace.recommendation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini settings for the daily-nutrition meal parser / recipe generator
 * (../../backend/recommendation/services/daily_nutrition.py).
 *
 * <ul>
 *   <li>{@code apiKey} — Django {@code settings.GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")}.
 *       Empty = both Gemini tiers are skipped (Django returns {@code []}/{@code None} before
 *       creating a client).</li>
 *   <li>{@code model} — hardcoded {@code "gemini-2.5-flash-lite"} in Django (both calls).</li>
 *   <li>{@code baseUrl} — the Gemini REST endpoint the google-genai SDK talks to; overridable only
 *       so an embedded HTTP server can stand in for it in tests.</li>
 *   <li>{@code parserTimeoutSeconds} — Django passes no timeout to the parser call; 0 here means
 *       "no read timeout" (same as Django). </li>
 *   <li>{@code recipeTimeoutSeconds} — the {@code timeout=3.0} Django intends for the recipe
 *       generator.</li>
 *   <li>{@code preserveRecipeTimeoutBug} — see {@code DailyNutritionService#callGeminiRecipeGenerator}:
 *       in Django, {@code generate_content(..., timeout=3.0)} raises {@code TypeError} (the installed
 *       google-genai 1.74 has no such kwarg; verified in backend/venv), which the bare
 *       {@code except} swallows — so the recipe tier NEVER works there. Default {@code false} =
 *       the working behavior (call Gemini with a 3 s timeout); {@code true} reproduces Django's
 *       always-None outcome.</li>
 * </ul>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.gemini")
public class GeminiProperties {

    private String apiKey = "";
    private String model = "gemini-2.5-flash-lite";
    private String baseUrl = "https://generativelanguage.googleapis.com";
    private int parserTimeoutSeconds = 0;
    private double recipeTimeoutSeconds = 3.0;
    private boolean preserveRecipeTimeoutBug = false;
}
