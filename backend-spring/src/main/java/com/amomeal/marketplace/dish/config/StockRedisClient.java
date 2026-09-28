package com.amomeal.marketplace.dish.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the dish module's own Redis connection, pointed at its own logical DB
 * index — the Spring equivalent of Django's separate stock client
 * ({@code _build_redis_client()} in ../../backend/dish/services/stock_reservation.py).
 *
 * <h2>Why the connection factory is built here instead of being a {@code @Bean}</h2>
 * Spring Boot's Redis autoconfiguration is guarded by
 * {@code @ConditionalOnMissingBean(RedisConnectionFactory.class)}. Declaring a
 * second {@code LettuceConnectionFactory} as a {@code @Bean} anywhere in the
 * context would therefore silently switch OFF the auto-configured one and break
 * {@code security.RefreshTokenService}'s {@code StringRedisTemplate}. Keeping
 * the factory as an internal field of this component (created in the
 * constructor, closed in {@link #shutdown()}) gives the dish module its own
 * isolated DB index with zero effect on the shared autoconfiguration.
 *
 * <h2>Connection target (Django backend-edit 9939179)</h2>
 * <ol>
 *   <li>{@code app.stock.redis-sentinels} set → Redis Sentinel
 *       ({@code STOCK_REDIS_SENTINELS}/{@code _SENTINEL_MASTER}/{@code _PASSWORD}, DB =
 *       {@code app.stock.redis-database});</li>
 *   <li>else {@code app.stock.redis-url} set → that URL ({@code STOCK_REDIS_URL}, the URL's
 *       own DB index);</li>
 *   <li>else the main connection details + {@code app.stock.redis-database}.</li>
 * </ol>
 * Connect AND command timeout = {@code app.stock.redis-socket-timeout-seconds} (Django
 * {@code STOCK_REDIS_SOCKET_TIMEOUT}, 0.3 s): a dead Redis costs milliseconds, then the
 * circuit breaker ({@link com.amomeal.marketplace.dish.service.StockRedisGateway}) skips it.
 *
 * <p>This class is a thin, exception-transparent wrapper: every method may throw a Spring
 * {@code DataAccessException} when Redis is unreachable. Outage handling lives in
 * {@code StockRedisGateway}, never here.
 */
@Component
@Slf4j
public class StockRedisClient {

    /** Verbatim copy of {@code _RESERVE_SCRIPT} in dish/services/stock_reservation.py. */
    private static final String RESERVE_SCRIPT = """
            local stock_key = KEYS[1]
            local qty = tonumber(ARGV[1])
            local seed = ARGV[2]

            if redis.call('EXISTS', stock_key) == 0 then
                redis.call('SET', stock_key, seed)
            end

            local current = tonumber(redis.call('GET', stock_key))
            if current < qty then
                return -1
            end

            redis.call('DECRBY', stock_key, qty)
            return 1
            """;

    /**
     * Verbatim copy of {@code _CREDIT_SCRIPT} (backend-edit 9939179): credits stock back ONLY
     * if the counter exists. A bare INCRBY on a missing key (Redis restarted / key deleted)
     * would create it with just {@code qty}, and because reserve() only seeds keys that don't
     * exist, that wrong value would stick. A missing key is left missing so the next reserve()
     * re-seeds it from Postgres.
     */
    private static final String CREDIT_SCRIPT = """
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
            end
            return 0
            """;

    private final LettuceConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> reserveScript;
    private final DefaultRedisScript<Long> creditScript;

    /**
     * Host/port come from {@link DataRedisConnectionDetails} rather than raw
     * {@code spring.data.redis.*} properties, because Testcontainers'
     * {@code @ServiceConnection} contributes a ConnectionDetails <i>bean</i> and
     * never sets those properties — reading the properties directly would
     * silently point the stock counters at localhost during tests. The
     * {@code @Value} fallbacks only apply if no such bean exists at all.
     */
    public StockRedisClient(ObjectProvider<DataRedisConnectionDetails> connectionDetails,
                            @Value("${spring.data.redis.host:localhost}") String fallbackHost,
                            @Value("${spring.data.redis.port:6379}") int fallbackPort,
                            @Value("${spring.data.redis.password:}") String fallbackPassword,
                            StockProperties properties) {
        Duration timeout = Duration.ofMillis(Math.max(1L, Math.round(properties.getRedisSocketTimeoutSeconds() * 1000)));
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(timeout)
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                        .build())
                .build();

        String target;
        if (!isBlank(properties.getRedisSentinels())) {
            Set<String> nodes = new LinkedHashSet<>();
            Arrays.stream(properties.getRedisSentinels().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).forEach(nodes::add);
            RedisSentinelConfiguration sentinel = new RedisSentinelConfiguration(properties.getRedisSentinelMaster(), nodes);
            sentinel.setDatabase(properties.getRedisDatabase());
            if (!isBlank(properties.getRedisPassword())) {
                sentinel.setPassword(properties.getRedisPassword());
            }
            this.connectionFactory = new LettuceConnectionFactory(sentinel, clientConfig);
            target = "sentinel " + properties.getRedisSentinelMaster() + "@" + nodes + " db=" + properties.getRedisDatabase();
        } else {
            RedisStandaloneConfiguration config;
            if (!isBlank(properties.getRedisUrl())) {
                RedisURI uri = RedisURI.create(properties.getRedisUrl());
                config = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
                config.setDatabase(uri.getDatabase());
                if (uri.getUsername() != null) {
                    config.setUsername(uri.getUsername());
                }
                if (uri.getPassword() != null && uri.getPassword().length > 0) {
                    config.setPassword(uri.getPassword());
                }
            } else {
                DataRedisConnectionDetails details = connectionDetails.getIfAvailable();
                String host = details != null ? details.getStandalone().getHost() : fallbackHost;
                int port = details != null ? details.getStandalone().getPort() : fallbackPort;
                String password = details != null ? details.getPassword() : fallbackPassword;
                config = new RedisStandaloneConfiguration(host, port);
                config.setDatabase(properties.getRedisDatabase());
                if (password != null && !password.isBlank()) {
                    config.setPassword(password);
                }
            }
            this.connectionFactory = new LettuceConnectionFactory(config, clientConfig);
            target = config.getHostName() + ":" + config.getPort() + " db=" + config.getDatabase();
        }
        this.connectionFactory.afterPropertiesSet();
        this.connectionFactory.start();

        this.redisTemplate = new StringRedisTemplate(this.connectionFactory);
        this.redisTemplate.afterPropertiesSet();

        this.reserveScript = new DefaultRedisScript<>(RESERVE_SCRIPT, Long.class);
        this.creditScript = new DefaultRedisScript<>(CREDIT_SCRIPT, Long.class);
        log.info("Stock Redis client configured for {} (timeout {} ms)", target, timeout.toMillis());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Django: {@code f"stock:avail:{dish_uid}:{available_date.isoformat()}"}. */
    public static String stockKey(UUID dishUid, LocalDate availableDate) {
        return "stock:avail:" + dishUid + ":" + availableDate;
    }

    public boolean exists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * Runs {@code _RESERVE_SCRIPT}.
     *
     * @return 1 when the decrement happened, -1 when there was not enough stock
     */
    public long reserve(String key, int quantity, long seed) {
        Long result = redisTemplate.execute(reserveScript, List.of(key),
                String.valueOf(quantity), String.valueOf(seed));
        return result == null ? -1L : result;
    }

    /** Runs {@code _CREDIT_SCRIPT}: INCRBY only if the counter exists (else leaves it missing). */
    public long credit(String key, long quantity) {
        Long result = redisTemplate.execute(creditScript, List.of(key), String.valueOf(quantity));
        return result == null ? 0L : result;
    }

    /** Plain {@code INCRBY} — creates a missing key. Kept for diagnostics; the service uses {@link #credit}. */
    public void incrementBy(String key, long delta) {
        redisTemplate.opsForValue().increment(key, delta);
    }

    /** Django: {@code self._redis.set(self._stock_key(...), seed)}. */
    public void set(String key, long value) {
        redisTemplate.opsForValue().set(key, String.valueOf(value));
    }

    /** Test/diagnostic read of the raw counter; null when the key was never touched. */
    public Long get(String key) {
        String raw = redisTemplate.opsForValue().get(key);
        return raw == null ? null : Long.valueOf(raw);
    }

    public void delete(String key) {
        redisTemplate.delete(key);
    }

    /** Django: {@code self._redis.delete(*keys)}. */
    public void deleteAll(Collection<String> keys) {
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @PreDestroy
    void shutdown() {
        connectionFactory.destroy();
    }
}
