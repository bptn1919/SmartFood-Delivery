package com.amomeal.marketplace.dish.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mirrors the Django settings the stock-hold mechanism reads (plus, since
 * backend-edit commit 9939179, the Redis-outage settings at the bottom)
 * (../../backend/marketplace/settings.py):
 * <ul>
 *   <li>{@code STOCK_HOLD_TTL_SECONDS} (default 900 = 15 minutes) -&gt;
 *       {@link #holdTtlSeconds};</li>
 *   <li>{@code STOCK_REDIS_URL} (default {@code redis://host:port/2}) -&gt; the
 *       {@link #redisDatabase} index, layered on the host/port Spring Boot
 *       already resolves from {@code spring.data.redis.*} (which Testcontainers
 *       also drives via {@code @ServiceConnection}). Only the DB <i>number</i> is
 *       configurable separately, because that is the only part of Django's URL
 *       that differs from the main connection.</li>
 * </ul>
 *
 * <p>CLAUDE.md §6 asks for one consistent answer to "how do the Redis logical
 * DBs stay separate". The answer this module establishes, matching the comment
 * already in application.yml: <b>a dedicated connection factory per logical DB
 * index</b> (see {@link StockRedisClient}), not a shared client with explicit
 * {@code SELECT n}. DB 0 stays the refresh-token store; DB 1 is stock holds.
 */
@ConfigurationProperties(prefix = "app.stock")
@Getter
@Setter
public class StockProperties {

    /** Django {@code STOCK_HOLD_TTL_SECONDS}. */
    private long holdTtlSeconds = 900;

    /** Logical Redis DB index for stock counters (Django used URL suffix /2). */
    private int redisDatabase = 1;

    /** Whether the {@code @Scheduled} expired-hold sweep runs (Django: Celery Beat every 30 s). */
    private boolean sweepEnabled = true;

    /** How many expired holds one sweep tick processes (Django slices {@code [:500]}). */
    private int sweepBatchSize = 500;

    // ---- Redis-outage tolerance (Django backend-edit 9939179, marketplace/settings.py) ----

    /**
     * Django {@code STOCK_REDIS_URL}: an optional dedicated stock Redis
     * ({@code redis://[:password@]host:port/db}). Empty = the main connection
     * ({@code spring.data.redis} / Testcontainers) with {@link #redisDatabase}.
     */
    private String redisUrl = "";

    /** Django {@code STOCK_REDIS_SENTINELS} ({@code "h1:26379,h2:26379"}); empty = no Sentinel. */
    private String redisSentinels = "";

    /** Django {@code STOCK_REDIS_SENTINEL_MASTER}. */
    private String redisSentinelMaster = "mymaster";

    /** Django {@code STOCK_REDIS_PASSWORD} (Sentinel mode; URL mode reads it from the URL). */
    private String redisPassword = "";

    /** Django {@code STOCK_REDIS_SOCKET_TIMEOUT} (0.3 s): connect + command timeout of the stock client. */
    private double redisSocketTimeoutSeconds = 0.3;

    /** Django {@code STOCK_REDIS_BREAKER_FAIL_MAX}: consecutive failures that open the breaker. */
    private int redisBreakerFailMax = 3;

    /** Django {@code STOCK_REDIS_BREAKER_RESET_SECONDS}: one probe per this window while open. */
    private double redisBreakerResetSeconds = 10;
}
