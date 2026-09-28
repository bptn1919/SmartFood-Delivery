package com.amomeal.marketplace;

import com.amomeal.marketplace.payment.support.FakePayOsServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// public: every module's integration test (not just the root package) needs to @Import this.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16"));
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(6379);
	}

	/**
	 * payment module: point every full-stack test at an in-process fake PayOS (real HTTP, real
	 * signatures — see {@link FakePayOsServer}) instead of the real gateway. Registered here,
	 * the one config every full-stack test imports, because once {@code payment} supplies the
	 * real {@code OrderPaymentGateway}, {@code order}'s own PayOS place-order tests go through
	 * PayOS too. Retry delays are shrunk so the payout retry paths stay fast.
	 */
	@Bean
	DynamicPropertyRegistrar fakePayOsProperties() {
		FakePayOsServer fake = FakePayOsServer.get();
		return registry -> {
			registry.add("app.payos.client-id", () -> FakePayOsServer.CLIENT_ID);
			registry.add("app.payos.api-key", () -> FakePayOsServer.API_KEY);
			registry.add("app.payos.checksum-key", () -> FakePayOsServer.CHECKSUM_KEY);
			registry.add("app.payos.api-url", fake::baseUrl);
			registry.add("app.payos.sdk-base-url", fake::baseUrl);
			registry.add("app.payos.return-url", () -> "https://fe.test/payment/return");
			registry.add("app.payos.cancel-url", () -> "https://fe.test/payment/cancel");
			registry.add("app.payos.sdk-retry-base-delay-ms", () -> "1");
			registry.add("app.payment.payout-retry-backoff-ms", () -> "1");
			registry.add("app.payment.wallet-chain-secret", () -> "test-wallet-chain-secret");
			registry.add("app.payment.event-secret", () -> "test-payment-event-secret");
		};
	}

}
