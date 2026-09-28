package com.amomeal.marketplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot port of ../backend (Django). See CLAUDE.md for conventions.
 *
 * {@code @EnableScheduling}/{@code @EnableAsync}/{@code @EnableRetry} replace
 * Celery for this project's only two background jobs (CLAUDE.md §6):
 * dish's stock-hold sweep (-> {@code @Scheduled}) and order's async
 * notification email (-> {@code @Async} + {@code @Retryable}). No message
 * broker needed.
 */
// UserDetailsServiceAutoConfiguration excluded: auth is entirely our own stateless JWT filter +
// AuthService (manual PasswordEncoder check) — we never use Spring Security's
// AuthenticationManager/UserDetailsService/DaoAuthenticationProvider flow, so leaving it enabled
// only produces a pointless "Using generated security password" warning on every boot.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
@EnableRetry
public class MarketplaceApplication {

	public static void main(String[] args) {
		SpringApplication.run(MarketplaceApplication.class, args);
	}

}
