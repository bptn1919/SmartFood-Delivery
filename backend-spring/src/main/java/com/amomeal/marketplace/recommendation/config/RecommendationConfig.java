package com.amomeal.marketplace.recommendation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * recommendation module wiring. The {@code recommendationClock} bean replaces Django's
 * {@code timezone.now()} / {@code timezone.localdate()} (TIME_ZONE = 'UTC', USE_TZ = True) so tests
 * can pin time; it is qualified to avoid clashing with any future project-wide Clock bean.
 */
@Configuration
public class RecommendationConfig {

    @Bean(name = "recommendationClock")
    public Clock recommendationClock() {
        return Clock.systemUTC();
    }
}
